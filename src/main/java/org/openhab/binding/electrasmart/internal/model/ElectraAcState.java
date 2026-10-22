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
package org.openhab.binding.electrasmart.internal.model;

import static org.openhab.binding.electrasmart.internal.ElectraSmartBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.electrasmart.internal.api.ElectraApiException;
import org.openhab.binding.electrasmart.internal.api.dto.CommandJson;
import org.openhab.binding.electrasmart.internal.api.dto.DeviceRecord;
import org.openhab.binding.electrasmart.internal.api.dto.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Holds the operation state of one Electra air conditioner.
 *
 * <p>
 * The Electra cloud is state based: a {@code SEND_COMMAND} must contain the complete {@code OPER} map, otherwise the
 * omitted fields are reset. The raw map is therefore kept as received and only the fields this binding controls are
 * modified. Unknown fields are preserved so that modes set from the mobile app or the infrared remote survive.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class ElectraAcState {

    private final Logger logger = LoggerFactory.getLogger(ElectraAcState.class);

    private static final String KEY_TURN_ON_OFF = "TURN_ON_OFF";
    private static final String KEY_AC_MODE = "AC_MODE";
    private static final String KEY_TARGET_TEMPERATURE = "SPT";
    private static final String KEY_FAN_SPEED = "FANSPD";
    private static final String KEY_VERTICAL_SWING = "VSWING";
    private static final String KEY_HORIZONTAL_SWING = "HSWING";
    private static final String KEY_SHABAT = "SHABAT";
    private static final String KEY_TURBO = "TURBO";
    private static final String KEY_SLEEP = "SLEEP";
    private static final String KEY_IFEEL = "IFEEL";
    private static final String KEY_AC_SOURCE = "AC_STSRC";
    private static final String KEY_INDOOR_TEMPERATURE = "I_RAT";
    private static final String KEY_CALCULATED_TEMPERATURE = "I_CALC_AT";
    private static final String KEY_LAST_RUNNING_MODE = "O_ODU_MODE";

    private static final String SOURCE_WIFI = "WI-FI";
    private static final String VALUE_ON = "ON";
    private static final String VALUE_OFF = "OFF";

    private final String deviceId;
    private final String mac;
    private final String name;
    private final String model;
    private final String manufacturer;

    private JsonObject oper = new JsonObject();
    private JsonObject diagL2 = new JsonObject();
    private int timeDelta = Integer.MAX_VALUE;
    private boolean hasTelemetry;
    private long lastCommandAt;

    public ElectraAcState(DeviceRecord device) {
        this.deviceId = device.getId();
        this.mac = device.getMac();
        this.name = device.getName();
        this.model = device.getModel();
        this.manufacturer = device.getManufacturer();
    }

    /**
     * Applies a telemetry block received from the cloud.
     *
     * @throws ElectraApiException when the embedded JSON cannot be parsed
     */
    public void update(Telemetry telemetry) throws ElectraApiException {
        CommandJson commandJson = telemetry.getCommandJson();
        if (commandJson == null || commandJson.getOper().isBlank() || commandJson.getDiagL2().isBlank()) {
            logger.debug("Skipping telemetry for {} ({}): incomplete payload", name, mac);
            return;
        }

        try {
            JsonObject operWrapper = JsonParser.parseString(commandJson.getOper()).getAsJsonObject();
            JsonObject diagWrapper = JsonParser.parseString(commandJson.getDiagL2()).getAsJsonObject();
            JsonElement operElement = operWrapper.get("OPER");
            JsonElement diagElement = diagWrapper.get("DIAG_L2");
            if (operElement == null || !operElement.isJsonObject() || diagElement == null
                    || !diagElement.isJsonObject()) {
                logger.debug("Skipping telemetry for {} ({}): missing OPER or DIAG_L2 block", name, mac);
                return;
            }
            this.oper = operElement.getAsJsonObject();
            this.diagL2 = diagElement.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ElectraApiException("Invalid telemetry payload for " + name + " (" + mac + ")", e);
        }

        this.timeDelta = telemetry.getTimeDelta();
        this.hasTelemetry = true;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getMac() {
        return mac;
    }

    public String getName() {
        return name;
    }

    public String getModel() {
        return model;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public boolean hasTelemetry() {
        return hasTelemetry;
    }

    /**
     * @param thresholdSeconds number of seconds without cloud contact after which the device counts as unavailable
     * @return {@code true} while the device reported to the cloud recently enough
     */
    public boolean isAvailable(int thresholdSeconds) {
        return hasTelemetry && timeDelta <= thresholdSeconds;
    }

    public int getTimeDelta() {
        return timeDelta;
    }

    public long getLastCommandAt() {
        return lastCommandAt;
    }

    public void markCommandSent(long timestamp) {
        this.lastCommandAt = timestamp;
    }

    public boolean isOn() {
        if (oper.has(KEY_TURN_ON_OFF)) {
            return VALUE_ON.equals(getString(KEY_TURN_ON_OFF));
        }
        return !MODE_STANDBY.equals(getString(KEY_AC_MODE));
    }

    /**
     * @return the mode the unit is currently running in, or the last mode the cloud remembers for a unit that is off
     */
    public String getMode() {
        String mode = getString(KEY_AC_MODE);
        if (OPERATIONAL_MODES.contains(mode)) {
            return mode;
        }
        String lastMode = getLastRunningMode();
        return lastMode != null ? lastMode : MODE_COOL;
    }

    public void setMode(String mode) {
        if (OPERATIONAL_MODES.contains(mode)) {
            oper.addProperty(KEY_AC_MODE, mode);
        }
    }

    /**
     * Turns the unit on while keeping the mode it ran in before.
     *
     * @return {@code false} when the unit is off and the cloud reports no previous mode, so the caller can choose a
     *         fallback
     */
    public boolean turnOn() {
        if (isOn()) {
            return true;
        }
        if (oper.has(KEY_TURN_ON_OFF)) {
            oper.addProperty(KEY_TURN_ON_OFF, VALUE_ON);
            return true;
        }
        String lastMode = getLastRunningMode();
        if (lastMode == null) {
            return false;
        }
        oper.addProperty(KEY_AC_MODE, lastMode);
        return true;
    }

    public void turnOff() {
        if (!isOn()) {
            return;
        }
        if (oper.has(KEY_TURN_ON_OFF)) {
            oper.addProperty(KEY_TURN_ON_OFF, VALUE_OFF);
        } else {
            oper.addProperty(KEY_AC_MODE, MODE_STANDBY);
        }
    }

    public int getTargetTemperature() {
        return getInt(oper, KEY_TARGET_TEMPERATURE, MIN_TEMPERATURE);
    }

    public void setTargetTemperature(int temperature) {
        int clamped = Math.max(MIN_TEMPERATURE, Math.min(MAX_TEMPERATURE, temperature));
        oper.addProperty(KEY_TARGET_TEMPERATURE, Integer.toString(clamped));
    }

    /**
     * @return the indoor temperature in degrees Celsius, or {@code null} when the unit does not report one
     */
    public @Nullable Integer getIndoorTemperature() {
        Integer raw = readInt(diagL2, KEY_CALCULATED_TEMPERATURE);
        if (raw == null) {
            raw = readInt(diagL2, KEY_INDOOR_TEMPERATURE);
        }
        return raw == null ? null : normalizeTemperature(raw);
    }

    public String getFanSpeed() {
        String speed = getString(KEY_FAN_SPEED);
        return FAN_SPEEDS.contains(speed) ? speed : FAN_AUTO;
    }

    public void setFanSpeed(String speed) {
        if (FAN_SPEEDS.contains(speed)) {
            oper.addProperty(KEY_FAN_SPEED, speed);
        }
    }

    public boolean supportsVerticalSwing() {
        return oper.has(KEY_VERTICAL_SWING);
    }

    public boolean supportsHorizontalSwing() {
        return oper.has(KEY_HORIZONTAL_SWING);
    }

    public boolean isVerticalSwing() {
        return VALUE_ON.equals(getString(KEY_VERTICAL_SWING));
    }

    public boolean isHorizontalSwing() {
        return VALUE_ON.equals(getString(KEY_HORIZONTAL_SWING));
    }

    public String getSwingMode() {
        boolean vertical = isVerticalSwing();
        boolean horizontal = isHorizontalSwing();
        if (vertical && horizontal) {
            return SWING_BOTH;
        }
        if (vertical) {
            return SWING_VERTICAL;
        }
        if (horizontal) {
            return SWING_HORIZONTAL;
        }
        return SWING_OFF;
    }

    public void setSwingMode(String mode) {
        boolean vertical = SWING_VERTICAL.equals(mode) || SWING_BOTH.equals(mode);
        boolean horizontal = SWING_HORIZONTAL.equals(mode) || SWING_BOTH.equals(mode);
        if (supportsVerticalSwing()) {
            oper.addProperty(KEY_VERTICAL_SWING, vertical ? VALUE_ON : VALUE_OFF);
        }
        if (supportsHorizontalSwing()) {
            oper.addProperty(KEY_HORIZONTAL_SWING, horizontal ? VALUE_ON : VALUE_OFF);
        }
    }

    public boolean supportsShabatMode() {
        return oper.has(KEY_SHABAT);
    }

    public boolean getShabatMode() {
        return VALUE_ON.equals(getString(KEY_SHABAT));
    }

    public void setShabatMode(boolean enabled) {
        if (supportsShabatMode()) {
            oper.addProperty(KEY_SHABAT, enabled ? VALUE_ON : VALUE_OFF);
        }
    }

    public boolean supportsTurboMode() {
        return oper.has(KEY_TURBO);
    }

    public boolean getTurboMode() {
        return VALUE_ON.equals(getString(KEY_TURBO));
    }

    public void setTurboMode(boolean enabled) {
        if (supportsTurboMode()) {
            oper.addProperty(KEY_TURBO, enabled ? VALUE_ON : VALUE_OFF);
        }
    }

    public boolean supportsSleepFunction() {
        return oper.has(KEY_SLEEP);
    }

    public boolean getSleepFunction() {
        return VALUE_ON.equals(getString(KEY_SLEEP));
    }

    public void setSleepFunction(boolean enabled) {
        if (supportsSleepFunction()) {
            oper.addProperty(KEY_SLEEP, enabled ? VALUE_ON : VALUE_OFF);
        }
    }

    public boolean supportsIFeel() {
        return oper.has(KEY_IFEEL);
    }

    public boolean getIFeel() {
        return VALUE_ON.equals(getString(KEY_IFEEL));
    }

    public void setIFeel(boolean enabled) {
        if (supportsIFeel()) {
            oper.addProperty(KEY_IFEEL, enabled ? VALUE_ON : VALUE_OFF);
        }
    }

    /**
     * @return a deep copy of the raw {@code OPER} map, e.g. to restore it when a command fails
     */
    public JsonObject copyOper() {
        return oper.deepCopy();
    }

    public void restoreOper(JsonObject state) {
        this.oper = state.deepCopy();
    }

    /**
     * @return the payload the cloud expects in {@code SEND_COMMAND.data.commandJson}
     */
    public JsonObject getOperationState() {
        JsonObject copy = oper.deepCopy();
        if (copy.has(KEY_AC_SOURCE)) {
            copy.addProperty(KEY_AC_SOURCE, SOURCE_WIFI);
        }
        JsonObject root = new JsonObject();
        root.add("OPER", copy);
        return root;
    }

    private @Nullable String getLastRunningMode() {
        String lastMode = getString(diagL2, KEY_LAST_RUNNING_MODE);
        if (OPERATIONAL_MODES.contains(lastMode)) {
            return lastMode;
        }
        return null;
    }

    private String getString(String key) {
        return getString(oper, key);
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return "";
        }
    }

    private static int getInt(JsonObject object, String key, int defaultValue) {
        Integer value = readInt(object, key);
        return value != null ? value : defaultValue;
    }

    private static @Nullable Integer readInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            try {
                return Integer.parseInt(element.getAsString().trim());
            } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e2) {
                return null;
            }
        }
    }

    /**
     * Some units report temperatures as a ×256 left-shifted integer, e.g. 5632 for 22 °C. Values above 100 °C are
     * implausible for a room, so only those are shifted.
     */
    private static int normalizeTemperature(int raw) {
        return raw > 100 ? raw >> 8 : raw;
    }
}
