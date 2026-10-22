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
package org.openhab.binding.electrasmart.internal.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.electrasmart.internal.api.dto.CommandResponse;
import org.openhab.binding.electrasmart.internal.api.dto.DeviceRecord;
import org.openhab.binding.electrasmart.internal.api.dto.Telemetry;
import org.openhab.binding.electrasmart.internal.model.ElectraAcState;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Client for the Electra Smart cloud API.
 *
 * <p>
 * The API is a single endpoint that takes a command name and a payload. Authentication is a two-step process: a
 * one-time password exchange returns a long-lived token, and the token is exchanged for a session id (SID) that
 * expires after 15 minutes. The cloud locks accounts that ask for a SID more than once within five minutes, so those
 * requests are cached and throttled here.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class ElectraApiClient {

    public static final String DEFAULT_BASE_URL = "https://app.ecpiot.co.il/mobile/mobilecommand";

    private static final int SID_LIFETIME_SECONDS = 15 * 60;
    private static final int SID_MIN_INTERVAL_SECONDS = 5 * 60;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    private static final String CMD_SEND_OTP = "SEND_OTP";
    private static final String CMD_CHECK_OTP = "CHECK_OTP";
    private static final String CMD_VALIDATE_TOKEN = "VALIDATE_TOKEN";
    private static final String CMD_GET_DEVICES = "GET_DEVICES";
    private static final String CMD_GET_LAST_TELEMETRY = "GET_LAST_TELEMETRY";
    private static final String CMD_SEND_COMMAND = "SEND_COMMAND";

    private static final String KEY_PVDID = "pvdid";
    private static final String KEY_ID = "id";
    private static final String KEY_CMD = "cmd";
    private static final String KEY_SID = "sid";
    private static final String KEY_DATA = "data";
    private static final String KEY_IMEI = "imei";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_CODE = "code";
    private static final String KEY_OS = "os";
    private static final String KEY_OS_VERSION = "osver";
    private static final String KEY_DEVICE_ID = "id";
    private static final String KEY_COMMAND_NAME = "commandName";
    private static final String KEY_COMMAND_JSON = "commandJson";

    private static final String INTRUDER_LOCKOUT = "Intruder lockout";
    private static final String OS_ANDROID = "android";
    private static final String OS_VERSION = "M4B30Z";
    private static final String USER_AGENT = "Electra Client";

    private final HttpClient httpClient;
    private final Gson gson;
    private final String baseUrl;
    private final LongSupplier clock;

    private @Nullable String imei;
    private @Nullable String token;
    private @Nullable String sid;
    private long sidExpiresAt;
    private long lastSidRequest;
    private @Nullable Runnable sidRequestListener;

    public ElectraApiClient(HttpClient httpClient, Gson gson) {
        this(httpClient, gson, DEFAULT_BASE_URL, () -> Instant.now().getEpochSecond());
    }

    public ElectraApiClient(HttpClient httpClient, Gson gson, String baseUrl) {
        this(httpClient, gson, baseUrl, () -> Instant.now().getEpochSecond());
    }

    public ElectraApiClient(HttpClient httpClient, Gson gson, String baseUrl, LongSupplier clock) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    /**
     * @return a random IMEI in the format the Electra app uses; it identifies this openHAB instance to the cloud and
     *         must stay stable once registered
     */
    public static String generateImei() {
        return "2b950000" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
    }

    public void setCredentials(@Nullable String imei, @Nullable String token) {
        this.imei = imei;
        String currentToken = this.token;
        if (token == null ? currentToken != null : !token.equals(currentToken)) {
            this.sid = null;
            this.sidExpiresAt = 0;
        }
        this.token = token;
    }

    public @Nullable String getImei() {
        return imei;
    }

    public @Nullable String getToken() {
        return token;
    }

    public void setLastSidRequest(long timestamp) {
        if (timestamp > lastSidRequest) {
            lastSidRequest = timestamp;
        }
    }

    public long getLastSidRequest() {
        return lastSidRequest;
    }

    /**
     * Forgets the five minute session id throttle. Called after a successful one-time password sign-in, which starts a
     * new authenticated session and is the supported way to recover from a lockout.
     */
    public void resetSidThrottle() {
        this.lastSidRequest = 0;
    }

    /**
     * Registers a callback that runs whenever a new SID was requested, so the caller can persist the timestamp and
     * respect the five minute throttle across restarts.
     */
    public void setSidRequestListener(Runnable listener) {
        this.sidRequestListener = listener;
    }

    public void invalidateSid() {
        this.sid = null;
        this.sidExpiresAt = 0;
    }

    /**
     * Requests a one-time password for the given phone number.
     */
    public CommandResponse sendOtp(String phoneNumber) throws ElectraApiException {
        JsonObject data = new JsonObject();
        data.addProperty(KEY_IMEI, requireImei());
        data.addProperty(KEY_PHONE, phoneNumber);
        return post(command(CMD_SEND_OTP, data));
    }

    /**
     * Exchanges a one-time password for a long-lived token and stores the token on success.
     */
    public CommandResponse checkOtp(String phoneNumber, String otp) throws ElectraApiException {
        JsonObject data = new JsonObject();
        data.addProperty(KEY_IMEI, requireImei());
        data.addProperty(KEY_PHONE, phoneNumber);
        data.addProperty(KEY_CODE, otp);
        data.addProperty(KEY_OS, OS_ANDROID);
        data.addProperty(KEY_OS_VERSION, OS_VERSION);

        CommandResponse response = post(command(CMD_CHECK_OTP, data));
        CommandResponse.Data payload = response.getData();
        if (response.isSuccess() && payload != null && !payload.getToken().isBlank()) {
            String newToken = payload.getToken();
            if (!newToken.equals(this.token)) {
                // The session id belongs to the previous token and must not be reused.
                this.sid = null;
                this.sidExpiresAt = 0;
            }
            this.token = newToken;
        }
        return response;
    }

    /**
     * Fetches all air conditioners of the account.
     */
    public List<DeviceRecord> fetchDevices() throws ElectraApiException {
        JsonObject payload = command(CMD_GET_DEVICES, null);
        payload.addProperty(KEY_SID, getSid());

        CommandResponse response = post(payload);
        if (!response.isSuccess()) {
            throw new ElectraApiException("Failed to fetch devices: " + describe(response));
        }
        CommandResponse.Data data = response.getData();
        if (data == null) {
            throw new ElectraApiException("Failed to fetch devices: the cloud returned no device list");
        }
        List<DeviceRecord> reportedDevices = data.getDevices();
        if (reportedDevices == null) {
            throw new ElectraApiException("Failed to fetch devices: the cloud returned no device list");
        }

        List<DeviceRecord> devices = new ArrayList<>();
        for (DeviceRecord device : reportedDevices) {
            if (device.isAirConditioner()) {
                devices.add(device);
            }
        }
        return devices;
    }

    /**
     * Fetches the last operation and diagnostics telemetry of one device.
     */
    public Telemetry fetchTelemetry(String deviceId) throws ElectraApiException {
        JsonObject data = new JsonObject();
        data.addProperty(KEY_DEVICE_ID, deviceId);
        data.addProperty(KEY_COMMAND_NAME, "OPER,DIAG_L2");

        JsonObject payload = command(CMD_GET_LAST_TELEMETRY, data);
        payload.addProperty(KEY_SID, getSid());

        CommandResponse response = post(payload);
        if (!response.isSuccess()) {
            throw new ElectraApiException("Failed to get the device state: " + describe(response));
        }

        CommandResponse.Data responseData = response.getData();
        Telemetry telemetry = new Telemetry();
        if (responseData != null) {
            Integer timeDelta = responseData.getTimeDelta();
            telemetry.setTimeDelta(timeDelta != null ? timeDelta : Integer.MAX_VALUE);
            telemetry.setCommandJson(responseData.getCommandJson());
        }
        return telemetry;
    }

    /**
     * Sends the complete operation state of a device to the cloud.
     */
    public CommandResponse sendCommand(ElectraAcState state) throws ElectraApiException {
        JsonObject data = new JsonObject();
        data.addProperty(KEY_DEVICE_ID, state.getDeviceId());
        data.addProperty(KEY_COMMAND_JSON, gson.toJson(state.getOperationState()));

        JsonObject payload = command(CMD_SEND_COMMAND, data);
        payload.addProperty(KEY_SID, getSid());

        CommandResponse response = post(payload);
        if (!response.isSuccess() || !response.isDataSuccess()) {
            throw new ElectraApiException("The cloud rejected the command: " + describe(response));
        }
        return response;
    }

    private String getSid() throws ElectraApiException {
        long now = clock.getAsLong();
        String cached = sid;
        if (cached != null && now < sidExpiresAt) {
            return cached;
        }

        if (lastSidRequest != 0 && now < lastSidRequest + SID_MIN_INTERVAL_SECONDS) {
            long waitSeconds = lastSidRequest + SID_MIN_INTERVAL_SECONDS - now;
            throw new ElectraApiException("A session id was requested less than five minutes ago; waiting "
                    + waitSeconds + " more seconds prevents an Electra account lockout");
        }

        String imeiValue = requireImei();
        String tokenValue = token;
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new ElectraAuthException("No token available, sign in with a one-time password first");
        }

        lastSidRequest = now;
        Runnable listener = sidRequestListener;
        if (listener != null) {
            listener.run();
        }

        JsonObject data = new JsonObject();
        data.addProperty(KEY_IMEI, imeiValue);
        data.addProperty(KEY_TOKEN, tokenValue);
        data.addProperty(KEY_OS, OS_ANDROID);
        data.addProperty(KEY_OS_VERSION, OS_VERSION);

        CommandResponse response = post(command(CMD_VALIDATE_TOKEN, data));
        CommandResponse.Data payload = response.getData();
        if (payload == null || payload.getSid().isBlank()) {
            String description = payload != null ? payload.getResDesc() : "";
            if (INTRUDER_LOCKOUT.equalsIgnoreCase(description)
                    || INTRUDER_LOCKOUT.equalsIgnoreCase(response.getDesc())) {
                throw new ElectraLockoutException(
                        "The Electra account is locked out; sign in again with a new one-time password");
            }
            throw new ElectraApiException(
                    "Failed to retrieve a session id: " + (description.isBlank() ? describe(response) : description));
        }

        String sessionId = payload.getSid();
        this.sid = sessionId;
        this.sidExpiresAt = now + SID_LIFETIME_SECONDS;
        return sessionId;
    }

    private CommandResponse post(JsonObject payload) throws ElectraApiException {
        ContentResponse response;
        try {
            Request request = httpClient.newRequest(baseUrl).method(HttpMethod.POST)
                    .header("accept", "application/json").timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .content(new StringContentProvider(gson.toJson(payload), StandardCharsets.UTF_8),
                            "application/json");
            // The shared openHAB client already sends its own User-Agent. A second one makes the Electra backend answer
            // with HTTP 400, so the header must be replaced rather than appended.
            request.getHeaders().put("user-agent", USER_AGENT);
            response = request.send();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ElectraApiException("Interrupted while talking to the Electra cloud", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new ElectraApiException("Failed to talk to the Electra cloud: " + e.getMessage(), e);
        }

        String body = response.getContentAsString();
        if (response.getStatus() != HttpStatus.OK_200) {
            throw new ElectraApiException(
                    "The Electra cloud responded with HTTP " + response.getStatus() + ": " + truncate(body));
        }
        if (body == null || body.isBlank()) {
            throw new ElectraApiException("The Electra cloud returned an empty response");
        }

        try {
            CommandResponse parsed = gson.fromJson(body, CommandResponse.class);
            if (parsed == null) {
                throw new ElectraApiException("The Electra cloud returned an empty response");
            }
            return parsed;
        } catch (JsonParseException e) {
            throw new ElectraApiException("The Electra cloud returned invalid JSON: " + truncate(body), e);
        }
    }

    private static JsonObject command(String command, @Nullable JsonObject data) {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_PVDID, 1);
        payload.addProperty(KEY_ID, 99);
        payload.addProperty(KEY_CMD, command);
        if (data != null) {
            payload.add(KEY_DATA, data);
        }
        return payload;
    }

    private String requireImei() throws ElectraApiException {
        String value = imei;
        if (value == null || value.isBlank()) {
            throw new ElectraApiException("No IMEI available; the bridge is not initialised yet");
        }
        return value;
    }

    private static String describe(CommandResponse response) {
        String description = response.getDesc();
        CommandResponse.Data data = response.getData();
        if (data != null && !data.getResDesc().isBlank()) {
            return data.getResDesc();
        }
        if (!description.isBlank()) {
            return description;
        }
        Integer result = data != null ? data.getRes() : null;
        return "status " + response.getStatus() + (result != null ? ", result " + result : "");
    }

    private static String truncate(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "...";
    }
}
