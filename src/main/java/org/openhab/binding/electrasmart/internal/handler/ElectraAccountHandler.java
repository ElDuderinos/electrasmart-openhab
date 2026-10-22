/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.electrasmart.internal.handler;

import static org.openhab.binding.electrasmart.internal.ElectraSmartBindingConstants.*;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.electrasmart.internal.api.ElectraApiClient;
import org.openhab.binding.electrasmart.internal.api.ElectraApiException;
import org.openhab.binding.electrasmart.internal.api.ElectraAuthException;
import org.openhab.binding.electrasmart.internal.api.ElectraLockoutException;
import org.openhab.binding.electrasmart.internal.api.dto.CommandResponse;
import org.openhab.binding.electrasmart.internal.api.dto.DeviceRecord;
import org.openhab.binding.electrasmart.internal.api.dto.Telemetry;
import org.openhab.binding.electrasmart.internal.config.AccountConfiguration;
import org.openhab.binding.electrasmart.internal.discovery.ElectraDiscoveryService;
import org.openhab.binding.electrasmart.internal.model.ElectraAcState;
import org.openhab.binding.electrasmart.internal.util.PhoneNumberUtils;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * The account bridge authenticates against the Electra cloud, polls the state of all linked air conditioners and
 * serialises every cloud call.
 *
 * <p>
 * Sign-in is a two-step process: a one-time password sent by SMS is exchanged for a long-lived token, which is then
 * exchanged for a short-lived session id. The token, IMEI and throttle timestamps are kept in the openHAB storage
 * service rather than in the thing configuration, so they survive restarts without exposing them in backup files.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class ElectraAccountHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(ElectraAccountHandler.class);

    private static final String STORAGE_KEY = "session";
    private static final int OTP_RESEND_INTERVAL_SECONDS = 120;
    private static final int RETRY_DELAY_SECONDS = 60;

    private final StorageService storageService;
    private final Gson gson = new GsonBuilder().create();
    private final ElectraApiClient client;
    private final Map<String, ElectraAcState> states = new ConcurrentHashMap<>();
    private final Map<String, ElectraAcHandler> acHandlers = new ConcurrentHashMap<>();
    private final AtomicLong initGeneration = new AtomicLong();
    private final Object apiLock = new Object();

    private volatile boolean disposed;
    private AccountConfiguration config = new AccountConfiguration();
    private StoredSession session = new StoredSession();
    private @Nullable Storage<StoredSession> storage;
    private @Nullable ScheduledFuture<?> pollJob;
    private int consecutiveFailures;

    public ElectraAccountHandler(Bridge bridge, HttpClient httpClient, StorageService storageService) {
        super(bridge);
        this.storageService = storageService;
        this.client = new ElectraApiClient(httpClient, gson);
        this.client.setSidRequestListener(this::persistSidRequest);
    }

    @Override
    public void initialize() {
        disposed = false;
        long generation = initGeneration.incrementAndGet();
        config = getConfigAs(AccountConfiguration.class);
        String configuredPhoneNumber = config.phoneNumber.trim();
        config.phoneNumber = PhoneNumberUtils.normalize(configuredPhoneNumber);
        if (!config.phoneNumber.equals(configuredPhoneNumber)) {
            logger.info("The configured phone number used an international prefix; using the local Israeli format");
        }
        config.otp = config.otp.trim();
        if (config.refreshInterval < MIN_REFRESH_INTERVAL_SECONDS) {
            config.refreshInterval = MIN_REFRESH_INTERVAL_SECONDS;
        }
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(() -> initializeInternal(generation));
    }

    private void initializeInternal(long generation) {
        if (!isCurrent(generation)) {
            return;
        }
        if (config.phoneNumber.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "A phone number is required to sign in to the Electra cloud");
            return;
        }

        loadSession();

        try {
            if (!config.otp.isBlank()) {
                // An explicitly entered code always wins, so a stale token or a lockout can be recovered from.
                if (!authenticateWithOtp(generation)) {
                    if (!isCurrent(generation) || session.token.isBlank()
                            || getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR) {
                        return;
                    }
                    // File-based thing configurations cannot drop the one-time password after sign-in. An expired
                    // code would then block every start even though a valid token is stored, so fall back to it.
                    logger.warn("The configured one-time password was rejected; using the stored session instead");
                    config.otp = "";
                    client.setCredentials(session.imei, session.token);
                }
            } else if (session.token.isBlank()) {
                if (sendOtpIfAllowed()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "A one-time password was sent by SMS. Enter it in the account configuration or use the "
                                    + "submitOtp action, then the bridge connects automatically.");
                } else if (getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR) {
                    scheduleRetry(generation);
                }
                return;
            }

            if (!isCurrent(generation)) {
                return;
            }

            client.setLastSidRequest(session.lastSidRequest);
            refreshDevices(generation);

            if (!isCurrent(generation)) {
                return;
            }

            consecutiveFailures = 0;
            updateStatus(ThingStatus.ONLINE);
            startPolling();
        } catch (ElectraLockoutException e) {
            stopPolling();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
        } catch (ElectraAuthException e) {
            stopPolling();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
        } catch (ElectraApiException e) {
            stopPolling();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            scheduleRetry(generation);
        }
    }

    private void loadSession() {
        Storage<StoredSession> newStorage = storageService.getStorage(thing.getUID().toString(),
                StoredSession.class.getClassLoader());
        this.storage = newStorage;

        StoredSession stored = newStorage.get(STORAGE_KEY);
        if (stored != null && !config.phoneNumber.equals(stored.phoneNumber)) {
            logger.debug("The stored Electra session belongs to another phone number, discarding it");
            newStorage.remove(STORAGE_KEY);
            stored = null;
        }

        session = stored != null ? stored : new StoredSession();
        session.phoneNumber = config.phoneNumber;
        if (session.imei.isBlank()) {
            session.imei = ElectraApiClient.generateImei();
            persistSession();
        }
        client.setCredentials(session.imei, session.token.isBlank() ? null : session.token);
    }

    /**
     * @return {@code true} when the bridge can continue with the new token
     */
    private boolean authenticateWithOtp(long generation) {
        if (!isCurrent(generation)) {
            return false;
        }
        try {
            CommandResponse response = client.checkOtp(config.phoneNumber, config.otp);
            String token = client.getToken();
            if (!response.isSuccess() || token == null || token.isBlank()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "The one-time password was rejected or has expired. Request a new one and enter it again.");
                return false;
            }
            session.token = token;
            session.lastSidRequest = 0;
            client.resetSidThrottle();
            String imei = client.getImei();
            if (imei != null) {
                session.imei = imei;
            }
            persistSession();
            logger.debug("Signed in to the Electra cloud");
            clearOtpConfiguration();
            config.otp = "";
            return true;
        } catch (ElectraApiException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Could not validate the one-time password: " + e.getMessage());
            scheduleRetry(generation);
            return false;
        }
    }

    private boolean sendOtpIfAllowed() {
        long now = Instant.now().getEpochSecond();
        if (session.lastOtpRequest != 0 && now < session.lastOtpRequest + OTP_RESEND_INTERVAL_SECONDS) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "A one-time password was requested less than two minutes ago. Enter the received code in the "
                            + "account configuration or use the submitOtp action.");
            return false;
        }

        try {
            CommandResponse response = client.sendOtp(config.phoneNumber);
            if (!response.isSuccess() || !response.isDataSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "The Electra cloud refused to send a one-time password. Check the phone number.");
                return false;
            }
            session.lastOtpRequest = now;
            persistSession();
            return true;
        } catch (ElectraApiException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Could not request a one-time password: " + e.getMessage());
            return false;
        }
    }

    private void refreshDevices(long generation) throws ElectraApiException {
        List<DeviceRecord> devices;
        synchronized (apiLock) {
            devices = client.fetchDevices();
        }
        if (!isCurrent(generation)) {
            return;
        }

        Map<String, ElectraAcState> newStates = new LinkedHashMap<>();
        for (DeviceRecord device : devices) {
            if (!isCurrent(generation)) {
                return;
            }
            ElectraAcState state = new ElectraAcState(device);
            try {
                Telemetry telemetry;
                synchronized (apiLock) {
                    telemetry = client.fetchTelemetry(device.getId());
                }
                state.update(telemetry);
            } catch (ElectraLockoutException e) {
                throw e;
            } catch (ElectraApiException e) {
                logger.debug("Could not fetch the initial state of {} ({}): {}", device.getName(), device.getMac(),
                        e.getMessage());
            }
            newStates.put(device.getId(), state);
        }

        states.clear();
        states.putAll(newStates);
        logger.debug("Discovered {} Electra air conditioner(s)", newStates.size());

        for (ElectraAcHandler handler : acHandlers.values()) {
            ElectraAcState state = newStates.get(handler.getDeviceId());
            if (state != null) {
                handler.onStateUpdated(state);
            }
        }
    }

    private void startPolling() {
        stopPolling();
        pollJob = scheduler.scheduleWithFixedDelay(this::poll, config.refreshInterval, config.refreshInterval,
                TimeUnit.SECONDS);
        logger.debug("Polling the Electra cloud every {} seconds", config.refreshInterval);
    }

    private void stopPolling() {
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(true);
            pollJob = null;
        }
    }

    private void poll() {
        ThingStatusInfo statusInfo = getThing().getStatusInfo();
        if (disposed || (statusInfo.getStatus() == ThingStatus.OFFLINE
                && statusInfo.getStatusDetail() != ThingStatusDetail.COMMUNICATION_ERROR)) {
            return;
        }

        Collection<ElectraAcHandler> handlers = acHandlers.values();
        if (handlers.isEmpty()) {
            return;
        }

        int polled = 0;
        int failed = 0;
        for (ElectraAcHandler handler : handlers) {
            if (disposed) {
                return;
            }
            if (handler.isRecentlyCommanded(API_SETTLE_SECONDS)) {
                continue;
            }

            polled++;
            String deviceId = handler.getDeviceId();
            try {
                @Nullable
                ElectraAcState state;
                synchronized (apiLock) {
                    Telemetry telemetry = client.fetchTelemetry(deviceId);
                    state = states.get(deviceId);
                    if (state != null) {
                        state.update(telemetry);
                    }
                }
                if (state != null) {
                    handler.onStateUpdated(state);
                }
            } catch (ElectraLockoutException e) {
                stopPolling();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
                return;
            } catch (ElectraApiException e) {
                failed++;
                logger.debug("Failed to poll {}: {}", deviceId, e.getMessage());
                String message = e.getMessage();
                handler.onPollFailed(message != null ? message : e.getClass().getSimpleName());
            }
        }

        if (polled > 0 && failed == polled) {
            // Only a total outage marks the bridge offline; one silent device must not hide the others.
            consecutiveFailures++;
            if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "The Electra cloud could not be reached");
            }
        } else {
            consecutiveFailures = 0;
            if (statusInfo.getStatus() == ThingStatus.OFFLINE
                    && statusInfo.getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR) {
                updateStatus(ThingStatus.ONLINE);
            }
        }
    }

    /**
     * A change to apply to a device state, executed while the bridge holds the API lock.
     */
    @FunctionalInterface
    public interface StateCommand {
        /**
         * @return {@code false} when the command does not apply to the device
         */
        boolean apply(ElectraAcState state);
    }

    /**
     * Applies a change to the current device state and sends it to the cloud. Mutation, comparison and the request are
     * executed under the API lock, so a concurrent poll cannot overwrite the change before it is sent. When the cloud
     * rejects the command the previous state is restored.
     *
     * @return {@code true} when a request was sent
     */
    public boolean sendCommand(ElectraAcState state, StateCommand command) throws ElectraApiException {
        synchronized (apiLock) {
            JsonObject before = state.copyOper();
            try {
                if (!command.apply(state)) {
                    return false;
                }
                if (before.equals(state.copyOper())) {
                    return false;
                }
                client.sendCommand(state);
                state.markCommandSent(Instant.now().getEpochSecond());
                return true;
            } catch (ElectraApiException e) {
                state.restoreOper(before);
                throw e;
            }
        }
    }

    public List<DeviceRecord> discoverDevices() throws ElectraApiException {
        synchronized (apiLock) {
            return client.fetchDevices();
        }
    }

    public @Nullable ElectraAcState getState(String deviceId) {
        return states.get(deviceId);
    }

    public void registerAcHandler(ElectraAcHandler handler) {
        acHandlers.put(handler.getDeviceId(), handler);
        ElectraAcState state = states.get(handler.getDeviceId());
        if (state != null) {
            handler.onStateUpdated(state);
        }
    }

    public void unregisterAcHandler(ElectraAcHandler handler) {
        acHandlers.remove(handler.getDeviceId(), handler);
    }

    /**
     * Requests a new one-time password, e.g. to recover from an intruder lockout.
     */
    public void requestOtp() {
        scheduler.execute(() -> {
            if (config.phoneNumber.isBlank()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "A phone number is required to sign in to the Electra cloud");
                return;
            }
            if (session.imei.isBlank()) {
                loadSession();
            }
            if (sendOtpIfAllowed()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "A one-time password was sent by SMS. Enter it in the account configuration or use the "
                                + "submitOtp action.");
            }
        });
    }

    /**
     * Validates a one-time password entered through a thing action.
     */
    public void submitOtp(String otp) {
        String code = otp.trim();
        if (code.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "The one-time password is empty");
            return;
        }
        scheduler.execute(() -> {
            if (config.phoneNumber.isBlank()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "A phone number is required to sign in to the Electra cloud");
                return;
            }
            if (session.imei.isBlank()) {
                loadSession();
            }
            try {
                CommandResponse response = client.checkOtp(config.phoneNumber, code);
                String token = client.getToken();
                if (!response.isSuccess() || token == null || token.isBlank()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "The one-time password was rejected or has expired. Request a new one and try again.");
                    return;
                }
                session.token = token;
                session.lastSidRequest = 0;
                client.resetSidThrottle();
                persistSession();
                clearOtpConfiguration();
                initialize();
            } catch (ElectraApiException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Could not validate the one-time password: " + e.getMessage());
            }
        });
    }

    /**
     * Drops the stored session and starts the sign-in flow again.
     */
    public void signOut() {
        scheduler.execute(() -> {
            session.token = "";
            persistSession();
            client.setCredentials(session.imei, null);
            client.invalidateSid();
            clearOtpConfiguration();
            initialize();
        });
    }

    private void clearOtpConfiguration() {
        Configuration configuration = editConfiguration();
        if (configuration.get(CONFIG_OTP) != null) {
            configuration.remove(CONFIG_OTP);
            updateConfiguration(configuration);
        }
    }

    private void scheduleRetry(long generation) {
        scheduler.schedule(() -> {
            if (isCurrent(generation)) {
                initialize();
            }
        }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isCurrent(long generation) {
        return !disposed && generation == initGeneration.get();
    }

    private void persistSession() {
        Storage<StoredSession> currentStorage = storage;
        if (currentStorage != null) {
            currentStorage.put(STORAGE_KEY, session);
        }
    }

    private void persistSidRequest() {
        session.lastSidRequest = client.getLastSidRequest();
        persistSession();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(ElectraDiscoveryService.class, ElectraAccountActions.class);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The bridge has no channels of its own.
    }

    @Override
    public void handleRemoval() {
        Storage<StoredSession> currentStorage = storage;
        if (currentStorage != null) {
            currentStorage.remove(STORAGE_KEY);
        }
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void dispose() {
        disposed = true;
        initGeneration.incrementAndGet();
        stopPolling();
        super.dispose();
    }
}
