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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.electrasmart.internal.api.dto.CommandJson;
import org.openhab.binding.electrasmart.internal.api.dto.CommandResponse;
import org.openhab.binding.electrasmart.internal.api.dto.DeviceRecord;
import org.openhab.binding.electrasmart.internal.api.dto.Telemetry;
import org.openhab.binding.electrasmart.internal.model.ElectraAcState;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Tests the request payloads and session handling of {@link ElectraApiClient} against a mock cloud.
 *
 * @author ElDuderinos - Initial contribution
 */
class ElectraApiClientTest {

    private static final String PATH = "/mobile/mobilecommand";
    private static final String IMEI = "2b95000012345678";
    private static final String TOKEN = "a-token";

    private static final String SID_RESPONSE = "{\"status\":0,\"desc\":\"\",\"data\":{\"res\":0,\"sid\":\"sid-1\"}}";

    private static final String DEVICES_RESPONSE = """
            {
              "status": 0,
              "desc": "",
              "data": {
                "devices": [
                  {
                    "id": 9999,
                    "name": "Living Room",
                    "mac": "aa:bb:cc:dd:ee:ff",
                    "model": "Electra-X",
                    "manufactor": "Electra",
                    "deviceTypeName": "A/C",
                    "sn": "123",
                    "deviceToken": "device-token"
                  },
                  {
                    "id": 10000,
                    "name": "Sensor",
                    "mac": "aa:bb:cc:dd:ee:00",
                    "deviceTypeName": "SENSOR"
                  }
                ],
                "res": 0,
                "res_desc": ""
              }
            }
            """;

    private static final String TELEMETRY_RESPONSE = """
            {
              "status": 0,
              "desc": "",
              "data": {
                "timeDelta": 15,
                "commandJson": {
                  "OPER": "{\\"OPER\\":{\\"TURN_ON_OFF\\":\\"ON\\",\\"AC_MODE\\":\\"COOL\\",\\"SPT\\":\\"22\\",\\"FANSPD\\":\\"AUTO\\",\\"AC_STSRC\\":\\"IR\\"}}",
                  "DIAG_L2": "{\\"DIAG_L2\\":{\\"I_RAT\\":\\"24\\",\\"O_ODU_MODE\\":\\"COOL\\"}}"
                },
                "res": 0,
                "res_desc": ""
              }
            }
            """;

    private WireMockServer server = null;
    private HttpClient httpClient = null;
    private ElectraApiClient client = null;

    private final Gson gson = new Gson();
    private final AtomicLong now = new AtomicLong(1_700_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        httpClient = new HttpClient();
        httpClient.start();
        client = new ElectraApiClient(httpClient, gson, server.baseUrl() + PATH, now::get);
        client.setCredentials(IMEI, TOKEN);
    }

    @AfterEach
    void tearDown() throws Exception {
        HttpClient currentClient = httpClient;
        if (currentClient != null) {
            currentClient.stop();
        }
        WireMockServer currentServer = server;
        if (currentServer != null) {
            currentServer.stop();
        }
    }

    @Test
    void sendsTheOtpPayload() throws Exception {
        stub("SEND_OTP", "{\"status\":0,\"data\":{\"res\":0}}");

        client.sendOtp("0501234567");

        server.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.cmd", equalTo("SEND_OTP")))
                .withRequestBody(matchingJsonPath("$.data.phone", equalTo("0501234567")))
                .withRequestBody(matchingJsonPath("$.data.imei", equalTo(IMEI)))
                .withRequestBody(matchingJsonPath("$.pvdid", equalTo("1"))));
    }

    @Test
    void sendsExactlyOneUserAgentHeader() throws Exception {
        stub("SEND_OTP", "{\"status\":0,\"data\":{\"res\":0}}");

        client.sendOtp("0501234567");

        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertEquals(1, requests.size());
        HttpHeader userAgent = Objects.requireNonNull(requests.get(0).header("user-agent"));
        assertEquals(List.of("Electra Client"), userAgent.values());
    }

    @Test
    void checksTheOtpAndKeepsTheToken() throws Exception {
        stub("CHECK_OTP", "{\"status\":0,\"data\":{\"res\":0,\"token\":\"new-token\"}}");

        CommandResponse response = client.checkOtp("0501234567", "123456");

        assertTrue(response.isDataSuccess());
        assertEquals("new-token", client.getToken());
        server.verify(
                postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.data.code", equalTo("123456")))
                        .withRequestBody(matchingJsonPath("$.data.os", equalTo("android"))));
    }

    @Test
    void fetchesOnlyAirConditioners() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_DEVICES", DEVICES_RESPONSE);

        List<DeviceRecord> devices = client.fetchDevices();

        assertEquals(1, devices.size());
        assertEquals("Living Room", devices.get(0).getName());
        assertEquals("9999", devices.get(0).getId());
    }

    @Test
    void fetchesTelemetry() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_LAST_TELEMETRY", TELEMETRY_RESPONSE);

        Telemetry telemetry = client.fetchTelemetry("9999");

        assertEquals(15, telemetry.getTimeDelta());
        CommandJson commandJson = Objects.requireNonNull(telemetry.getCommandJson());
        assertTrue(commandJson.getOper().contains("AC_MODE"));
        assertTrue(commandJson.getDiagL2().contains("I_RAT"));
    }

    @Test
    void sendsTheCompleteOperationState() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_LAST_TELEMETRY", TELEMETRY_RESPONSE);
        stub("SEND_COMMAND", "{\"status\":0,\"data\":{\"res\":0}}");

        ElectraAcState state = new ElectraAcState(device());
        state.update(client.fetchTelemetry("9999"));
        state.setTargetTemperature(23);
        state.setFanSpeed("HIGH");

        client.sendCommand(state);

        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.data.commandJson", containing("\"SPT\":\"23\"")))
                .withRequestBody(matchingJsonPath("$.data.commandJson", containing("WI-FI")))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("9999"))));
    }

    @Test
    void reusesTheSessionId() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_DEVICES", DEVICES_RESPONSE);

        client.fetchDevices();
        client.fetchDevices();

        server.verify(exactly(1), postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.cmd", equalTo("VALIDATE_TOKEN"))));
    }

    @Test
    void throttlesSessionRequests() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_DEVICES", DEVICES_RESPONSE);

        client.fetchDevices();
        client.invalidateSid();
        now.addAndGet(60);

        ElectraApiException exception = assertThrows(ElectraApiException.class, () -> client.fetchDevices());
        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("five minutes"));
    }

    @Test
    void aNewSignInClearsTheSessionThrottle() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_DEVICES", DEVICES_RESPONSE);
        stub("CHECK_OTP", "{\"status\":0,\"data\":{\"res\":0,\"token\":\"new-token\"}}");

        client.fetchDevices();
        client.invalidateSid();
        now.addAndGet(60);
        assertThrows(ElectraApiException.class, () -> client.fetchDevices());

        client.checkOtp("0501234567", "123456");
        client.resetSidThrottle();

        assertEquals(1, client.fetchDevices().size());
        server.verify(exactly(2), postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.cmd", equalTo("VALIDATE_TOKEN"))));
    }

    @Test
    void aNewTokenInvalidatesTheCachedSessionId() throws Exception {
        stub("VALIDATE_TOKEN", SID_RESPONSE);
        stub("GET_DEVICES", DEVICES_RESPONSE);
        stub("CHECK_OTP", "{\"status\":0,\"data\":{\"res\":0,\"token\":\"new-token\"}}");

        client.fetchDevices();
        client.checkOtp("0501234567", "123456");
        // Still inside the 15 minute lifetime of the cached session id, but past the 5 minute request throttle.
        now.addAndGet(301);
        client.fetchDevices();

        server.verify(exactly(2), postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.cmd", equalTo("VALIDATE_TOKEN"))));
    }

    @Test
    void detectsAnIntruderLockout() throws Exception {
        stub("VALIDATE_TOKEN", "{\"status\":0,\"data\":{\"res\":1,\"res_desc\":\"Intruder lockout\"}}");

        assertThrows(ElectraLockoutException.class, () -> client.fetchDevices());
    }

    @Test
    void reportsAnHttpError() throws Exception {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503).withBody("unavailable")));

        ElectraApiException exception = assertThrows(ElectraApiException.class, () -> client.fetchDevices());
        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("503"));
    }

    private void stub(String command, String body) {
        server.stubFor(post(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.cmd", equalTo(command)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private DeviceRecord device() {
        JsonObject device = new JsonObject();
        device.addProperty("id", 9999);
        device.addProperty("name", "Living Room");
        device.addProperty("mac", "aa:bb:cc:dd:ee:ff");
        device.addProperty("model", "Electra-X");
        device.addProperty("manufactor", "Electra");
        device.addProperty("deviceTypeName", "A/C");
        return Objects.requireNonNull(gson.fromJson(device, DeviceRecord.class));
    }
}
