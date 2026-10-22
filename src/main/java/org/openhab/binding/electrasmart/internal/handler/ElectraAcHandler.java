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
import java.util.Locale;
import java.util.Map;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.electrasmart.internal.api.ElectraApiException;
import org.openhab.binding.electrasmart.internal.config.AcConfiguration;
import org.openhab.binding.electrasmart.internal.model.ElectraAcState;
import org.openhab.core.i18n.UnitProvider;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Electra air conditioner. Commands are applied to a copy of the last known cloud state and sent as a complete
 * operation map, because the cloud resets any field that is omitted.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class ElectraAcHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(ElectraAcHandler.class);

    private final boolean imperialUnits;

    private @Nullable ElectraAccountHandler bridgeHandler;
    private String deviceId = "";
    private int consecutiveFailures;

    public ElectraAcHandler(Thing thing, UnitProvider unitProvider) {
        super(thing);
        this.imperialUnits = unitProvider.getMeasurementSystem() instanceof ImperialUnits;
    }

    public String getDeviceId() {
        return deviceId;
    }

    @Override
    public void initialize() {
        AcConfiguration config = getConfigAs(AcConfiguration.class);
        deviceId = config.deviceId.trim();
        consecutiveFailures = 0;
        if (deviceId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "The device id is missing; run discovery or set it manually");
            return;
        }

        Thing bridge = getBridge();
        ThingHandler handler = bridge != null ? bridge.getHandler() : null;
        if (!(handler instanceof ElectraAccountHandler accountHandler)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                    "The Electra Smart account bridge is not available");
            return;
        }

        bridgeHandler = accountHandler;
        accountHandler.registerAcHandler(this);

        if (accountHandler.getThing().getStatus() == ThingStatus.ONLINE) {
            ElectraAcState state = accountHandler.getState(deviceId);
            if (state != null) {
                onStateUpdated(state);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "The configured device id is not part of this Electra Smart account");
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    "The Electra Smart account bridge is offline");
        }
    }

    @Override
    public void dispose() {
        ElectraAccountHandler accountHandler = bridgeHandler;
        if (accountHandler != null) {
            accountHandler.unregisterAcHandler(this);
        }
        bridgeHandler = null;
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            ElectraAccountHandler accountHandler = accountHandler();
            if (accountHandler == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                        "The Electra Smart account bridge is not available");
                return;
            }
            accountHandler.registerAcHandler(this);
            ElectraAcState state = accountHandler.getState(deviceId);
            if (state != null) {
                onStateUpdated(state);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "The configured device id is not part of this Electra Smart account");
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    "The Electra Smart account bridge is offline");
        }
    }

    /**
     * Called by the bridge whenever fresh telemetry was applied to this device.
     */
    public void onStateUpdated(ElectraAcState state) {
        if (!deviceId.equals(state.getDeviceId())) {
            return;
        }

        consecutiveFailures = 0;
        updateDeviceProperties(state);
        updateChannels(state);

        ElectraAccountHandler accountHandler = accountHandler();
        if (accountHandler == null || accountHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    "The Electra Smart account bridge is offline");
        } else if (!state.hasTelemetry()) {
            updateStatus(ThingStatus.UNKNOWN);
        } else if (!state.isAvailable(UNAVAILABLE_THRESHOLD_SECONDS)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.GONE, "The air conditioner has not contacted the "
                    + "Electra cloud for " + state.getTimeDelta() + " seconds");
        } else if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    /**
     * Called by the bridge when polling this device failed. Only this thing is marked unavailable after a few
     * consecutive failures, so a silent unit does not take the other air conditioners offline.
     */
    public void onPollFailed(String message) {
        consecutiveFailures++;
        if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Failed to read the state from the Electra cloud: " + message);
        }
    }

    /**
     * @return {@code true} while a command was sent recently and the cloud still reports the previous state
     */
    public boolean isRecentlyCommanded(int settleSeconds) {
        ElectraAcState state = currentState();
        return state != null && state.getLastCommandAt() != 0
                && Instant.now().getEpochSecond() < state.getLastCommandAt() + settleSeconds;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            ElectraAcState state = currentState();
            if (state != null) {
                updateChannels(state);
            }
            return;
        }

        String channelId = channelUID.getId();
        scheduler.execute(() -> executeCommand(channelId, command));
    }

    private void executeCommand(String channelId, Command command) {
        ElectraAcState state = currentState();
        ElectraAccountHandler accountHandler = accountHandler();
        if (state == null || accountHandler == null) {
            logger.debug("Ignoring command for channel {}: the device state is not available", channelId);
            return;
        }

        try {
            if (!accountHandler.sendCommand(state, deviceState -> applyCommand(deviceState, channelId, command))) {
                logger.debug("Ignoring command for channel {}: the state did not change", channelId);
            }
            updateChannels(state);
        } catch (ElectraApiException e) {
            logger.warn("Failed to send a command to {} ({}): {}", state.getName(), state.getMac(), e.getMessage());
            updateChannels(state);
        }
    }

    /**
     * @return {@code false} when the command does not apply to this device or channel
     */
    private boolean applyCommand(ElectraAcState state, String channelId, Command command) {
        switch (channelId) {
            case CHANNEL_POWER:
                if (command instanceof OnOffType onOff) {
                    if (onOff == OnOffType.ON) {
                        if (!state.turnOn()) {
                            state.setMode(MODE_COOL);
                        }
                    } else {
                        state.turnOff();
                    }
                    return true;
                }
                return false;

            case CHANNEL_OPERATIONAL_MODE:
                if (command instanceof StringType string) {
                    String mode = string.toFullString().toUpperCase(Locale.ROOT);
                    if (!OPERATIONAL_MODES.contains(mode)) {
                        return false;
                    }
                    state.setMode(mode);
                    state.turnOn();
                    return true;
                }
                return false;

            case CHANNEL_TARGET_TEMPERATURE:
                if (command instanceof QuantityType<?> quantity) {
                    QuantityType<?> celsius = quantity.toUnit(SIUnits.CELSIUS);
                    if (celsius == null) {
                        return false;
                    }
                    double value = celsius.doubleValue();
                    if (Double.isNaN(value)) {
                        return false;
                    }
                    state.setTargetTemperature((int) Math.round(value));
                    return true;
                }
                if (command instanceof DecimalType decimal) {
                    state.setTargetTemperature((int) Math.round(decimal.doubleValue()));
                    return true;
                }
                return false;

            case CHANNEL_FAN_SPEED:
                if (command instanceof StringType string) {
                    String speed = string.toFullString().toUpperCase(Locale.ROOT);
                    if (!FAN_SPEEDS.contains(speed)) {
                        return false;
                    }
                    state.setFanSpeed(speed);
                    return true;
                }
                return false;

            case CHANNEL_SWING_MODE:
                if (command instanceof StringType string) {
                    String swingMode = string.toFullString().toUpperCase(Locale.ROOT);
                    if (!SWING_MODES.contains(swingMode)) {
                        return false;
                    }
                    state.setSwingMode(swingMode);
                    return true;
                }
                return false;

            case CHANNEL_SHABAT_MODE:
                if (command instanceof OnOffType onOff) {
                    state.setShabatMode(onOff == OnOffType.ON);
                    return true;
                }
                return false;

            case CHANNEL_TURBO_MODE:
                if (command instanceof OnOffType onOff) {
                    state.setTurboMode(onOff == OnOffType.ON);
                    return true;
                }
                return false;

            case CHANNEL_SLEEP_FUNCTION:
                if (command instanceof OnOffType onOff) {
                    state.setSleepFunction(onOff == OnOffType.ON);
                    return true;
                }
                return false;

            case CHANNEL_IFEEL:
                if (command instanceof OnOffType onOff) {
                    state.setIFeel(onOff == OnOffType.ON);
                    return true;
                }
                return false;

            default:
                logger.debug("Channel {} is read-only or unknown", channelId);
                return false;
        }
    }

    private void updateChannels(ElectraAcState state) {
        updateState(CHANNEL_POWER, OnOffType.from(state.isOn()));
        updateState(CHANNEL_OPERATIONAL_MODE, new StringType(state.getMode()));
        updateState(CHANNEL_TARGET_TEMPERATURE, toChannelTemperature(state.getTargetTemperature()));

        Integer indoorTemperature = state.getIndoorTemperature();
        updateState(CHANNEL_INDOOR_TEMPERATURE,
                indoorTemperature == null ? UnDefType.UNDEF : toChannelTemperature(indoorTemperature));

        updateState(CHANNEL_FAN_SPEED, new StringType(state.getFanSpeed()));
        updateState(CHANNEL_SWING_MODE, new StringType(state.getSwingMode()));

        updateState(CHANNEL_SHABAT_MODE,
                state.supportsShabatMode() ? OnOffType.from(state.getShabatMode()) : UnDefType.UNDEF);
        updateState(CHANNEL_TURBO_MODE,
                state.supportsTurboMode() ? OnOffType.from(state.getTurboMode()) : UnDefType.UNDEF);
        updateState(CHANNEL_SLEEP_FUNCTION,
                state.supportsSleepFunction() ? OnOffType.from(state.getSleepFunction()) : UnDefType.UNDEF);
        updateState(CHANNEL_IFEEL, state.supportsIFeel() ? OnOffType.from(state.getIFeel()) : UnDefType.UNDEF);

        updateState(CHANNEL_LAST_CONTACT,
                state.hasTelemetry() ? new QuantityType<>(state.getTimeDelta(), Units.SECOND) : UnDefType.UNDEF);
    }

    private State toChannelTemperature(int celsius) {
        QuantityType<Temperature> value = new QuantityType<>(celsius, SIUnits.CELSIUS);
        if (!imperialUnits) {
            return value;
        }
        QuantityType<Temperature> converted = value.toUnit(ImperialUnits.FAHRENHEIT);
        return converted != null ? converted : UnDefType.UNDEF;
    }

    private void updateDeviceProperties(ElectraAcState state) {
        Map<String, String> properties = editProperties();
        boolean changed = false;
        changed |= putIfAbsent(properties, PROPERTY_MAC, state.getMac());
        changed |= putIfAbsent(properties, PROPERTY_MODEL, state.getModel());
        changed |= putIfAbsent(properties, PROPERTY_MANUFACTURER, state.getManufacturer());
        if (changed) {
            updateProperties(properties);
        }
    }

    private static boolean putIfAbsent(Map<String, String> properties, String key, String value) {
        if (value.isBlank() || properties.containsKey(key)) {
            return false;
        }
        properties.put(key, value);
        return true;
    }

    private @Nullable ElectraAccountHandler accountHandler() {
        ElectraAccountHandler handler = bridgeHandler;
        if (handler != null) {
            return handler;
        }
        Thing bridge = getBridge();
        ThingHandler thingHandler = bridge != null ? bridge.getHandler() : null;
        if (thingHandler instanceof ElectraAccountHandler accountHandler) {
            bridgeHandler = accountHandler;
            return accountHandler;
        }
        return null;
    }

    private @Nullable ElectraAcState currentState() {
        ElectraAccountHandler accountHandler = accountHandler();
        return accountHandler != null ? accountHandler.getState(deviceId) : null;
    }
}
