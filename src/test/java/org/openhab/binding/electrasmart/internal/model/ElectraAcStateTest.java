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

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.electrasmart.internal.api.dto.CommandJson;
import org.openhab.binding.electrasmart.internal.api.dto.DeviceRecord;
import org.openhab.binding.electrasmart.internal.api.dto.Telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Tests for {@link ElectraAcState}.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
class ElectraAcStateTest {

    private static final Gson GSON = new Gson();

    @Test
    void parsesTelemetry() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "HIGH"), diag("24"));

        assertTrue(state.hasTelemetry());
        assertTrue(state.isOn());
        assertEquals("COOL", state.getMode());
        assertEquals(22, state.getTargetTemperature());
        assertEquals("HIGH", state.getFanSpeed());
        assertEquals(Integer.valueOf(24), state.getIndoorTemperature());
        assertEquals(5, state.getTimeDelta());
    }

    @Test
    void normalizesShiftedIndoorTemperature() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), diag("5632"));

        assertEquals(Integer.valueOf(22), state.getIndoorTemperature());
    }

    @Test
    void usesCalculatedTemperatureWhenRawIsMissing() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of("I_CALC_AT", "5888"));

        assertEquals(Integer.valueOf(23), state.getIndoorTemperature());
    }

    @Test
    void prefersCalculatedTemperatureOverRawTemperature() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of("I_RAT", "24", "I_CALC_AT", "5888"));

        assertEquals(Integer.valueOf(23), state.getIndoorTemperature());
    }

    @Test
    void returnsNullWhenNoIndoorTemperatureIsReported() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());

        assertNull(state.getIndoorTemperature());
    }

    @Test
    void turningOffUsesStandbyWithoutPowerFlag() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());

        state.turnOff();

        assertFalse(state.isOn());
        assertEquals("STBY", operationState(state).getAsJsonObject("OPER").get("AC_MODE").getAsString());
    }

    @Test
    void turningOffUsesPowerFlagWhenPresent() throws Exception {
        Map<String, String> oper = oper("COOL", "22", "AUTO");
        oper.put("TURN_ON_OFF", "ON");
        ElectraAcState state = state(oper, Map.of());

        state.turnOff();

        assertFalse(state.isOn());
        JsonObject operation = operationState(state).getAsJsonObject("OPER");
        assertEquals("OFF", operation.get("TURN_ON_OFF").getAsString());
        assertEquals("COOL", operation.get("AC_MODE").getAsString());
    }

    @Test
    void turningOnRestoresLastRunningMode() throws Exception {
        ElectraAcState state = state(oper("STBY", "22", "AUTO"), Map.of("O_ODU_MODE", "HEAT"));

        assertTrue(state.turnOn());

        assertTrue(state.isOn());
        assertEquals("HEAT", state.getMode());
    }

    @Test
    void turningOnReportsWhenNoPreviousModeIsKnown() throws Exception {
        ElectraAcState state = state(oper("STBY", "22", "AUTO"), Map.of());

        assertFalse(state.turnOn());
        assertFalse(state.isOn());
    }

    @Test
    void ignoresUnknownModes() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());

        state.setMode("TURBO");

        assertEquals("COOL", state.getMode());
    }

    @Test
    void clampsTargetTemperature() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());

        state.setTargetTemperature(12);
        assertEquals(17, state.getTargetTemperature());

        state.setTargetTemperature(35);
        assertEquals(30, state.getTargetTemperature());
    }

    @Test
    void swingOnlyChangesSupportedDirections() throws Exception {
        Map<String, String> oper = oper("COOL", "22", "AUTO");
        oper.put("VSWING", "OFF");
        ElectraAcState state = state(oper, Map.of());

        state.setSwingMode("BOTH");

        JsonObject operation = operationState(state).getAsJsonObject("OPER");
        assertEquals("ON", operation.get("VSWING").getAsString());
        assertFalse(operation.has("HSWING"));
        assertFalse(state.supportsHorizontalSwing());
    }

    @Test
    void combinesSwingDirections() throws Exception {
        Map<String, String> oper = oper("COOL", "22", "AUTO");
        oper.put("VSWING", "ON");
        oper.put("HSWING", "ON");
        ElectraAcState state = state(oper, Map.of());

        assertEquals("BOTH", state.getSwingMode());

        state.setSwingMode("VERTICAL");
        assertEquals("VERTICAL", state.getSwingMode());
    }

    @Test
    void shabatModeRequiresTheField() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());

        assertFalse(state.supportsShabatMode());
        state.setShabatMode(true);
        assertFalse(state.supportsShabatMode());
    }

    @Test
    void shabatModeIsWrittenWhenSupported() throws Exception {
        Map<String, String> oper = oper("COOL", "22", "AUTO");
        oper.put("SHABAT", "OFF");
        ElectraAcState state = state(oper, Map.of());

        state.setShabatMode(true);

        assertTrue(state.getShabatMode());
        assertEquals("ON", operationState(state).getAsJsonObject("OPER").get("SHABAT").getAsString());
    }

    @Test
    void commandPayloadKeepsUnknownFieldsAndMarksTheSource() throws Exception {
        Map<String, String> oper = oper("COOL", "22", "AUTO");
        oper.put("SLEEP", "ON");
        oper.put("AC_STSRC", "IR");
        ElectraAcState state = state(oper, Map.of());

        JsonObject operation = operationState(state).getAsJsonObject("OPER");

        assertEquals("ON", operation.get("SLEEP").getAsString());
        assertEquals("WI-FI", operation.get("AC_STSRC").getAsString());
    }

    @Test
    void restoresThePreviousState() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());
        JsonObject before = state.copyOper();

        state.setMode("HEAT");
        state.setTargetTemperature(28);
        state.restoreOper(before);

        assertEquals("COOL", state.getMode());
        assertEquals(22, state.getTargetTemperature());
    }

    @Test
    void availabilityUsesTheCloudContactTime() throws Exception {
        ElectraAcState state = state(oper("COOL", "22", "AUTO"), Map.of());

        assertTrue(state.isAvailable(120));

        Telemetry telemetry = telemetry(oper("COOL", "22", "AUTO"), Map.of());
        telemetry.setTimeDelta(121);
        state.update(telemetry);

        assertFalse(state.isAvailable(120));
    }

    @Test
    void ignoresIncompleteTelemetry() throws Exception {
        ElectraAcState state = new ElectraAcState(device());
        Telemetry telemetry = new Telemetry();
        telemetry.setTimeDelta(3);
        telemetry.setCommandJson(
                Objects.requireNonNull(GSON.fromJson("{\"OPER\":\"\",\"DIAG_L2\":\"\"}", CommandJson.class)));

        state.update(telemetry);

        assertFalse(state.hasTelemetry());
    }

    private static JsonObject operationState(ElectraAcState state) {
        return state.getOperationState();
    }

    private static Map<String, String> oper(String mode, String target, String fan) {
        Map<String, String> oper = new LinkedHashMap<>();
        oper.put("AC_MODE", mode);
        oper.put("SPT", target);
        oper.put("FANSPD", fan);
        return oper;
    }

    private static Map<String, String> diag(String indoor) {
        return Map.of("I_RAT", indoor);
    }

    private static ElectraAcState state(Map<String, String> oper, Map<String, String> diag) throws Exception {
        ElectraAcState state = new ElectraAcState(device());
        state.update(telemetry(oper, diag));
        return state;
    }

    private static Telemetry telemetry(Map<String, String> oper, Map<String, String> diag) {
        JsonObject operObject = new JsonObject();
        oper.forEach(operObject::addProperty);
        JsonObject diagObject = new JsonObject();
        diag.forEach(diagObject::addProperty);

        JsonObject commandJson = new JsonObject();
        commandJson.addProperty("OPER", wrap("OPER", operObject));
        commandJson.addProperty("DIAG_L2", wrap("DIAG_L2", diagObject));

        JsonObject payload = new JsonObject();
        payload.addProperty("timeDelta", 5);
        payload.add("commandJson", commandJson);
        return Objects.requireNonNull(GSON.fromJson(payload, Telemetry.class));
    }

    private static String wrap(String key, JsonObject value) {
        JsonObject root = new JsonObject();
        root.add(key, value);
        return root.toString();
    }

    private static DeviceRecord device() {
        return Objects.requireNonNull(GSON.fromJson("""
                {
                  "id": 9999,
                  "name": "Living Room",
                  "mac": "aa:bb:cc:dd:ee:ff",
                  "model": "Electra-X",
                  "manufactor": "Electra",
                  "deviceTypeName": "A/C"
                }
                """, DeviceRecord.class));
    }
}
