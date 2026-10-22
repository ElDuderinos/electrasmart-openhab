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
package org.openhab.binding.electrasmart.internal.api.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

/**
 * One device record as returned by the Electra {@code GET_DEVICES} command.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class DeviceRecord {

    /**
     * The cloud reports the id sometimes as a number and sometimes as a string, so it is kept as a JSON element.
     */
    @SerializedName("id")
    private @Nullable JsonElement id;

    private @Nullable String name;
    private @Nullable String mac;
    private @Nullable String model;
    private @Nullable String regdate;
    private @Nullable String status;

    @SerializedName("sn")
    private @Nullable String serialNumber;

    @SerializedName("manufactor")
    private @Nullable String manufacturer;

    @SerializedName("deviceTypeName")
    private @Nullable String deviceTypeName;

    @SerializedName("deviceToken")
    private @Nullable String deviceToken;

    public String getId() {
        JsonElement element = id;
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    public String getName() {
        String value = name;
        return value != null ? value : "";
    }

    public String getMac() {
        String value = mac;
        return value != null ? value : "";
    }

    public String getModel() {
        String value = model;
        return value != null ? value : "";
    }

    public String getRegdate() {
        String value = regdate;
        return value != null ? value : "";
    }

    public String getStatus() {
        String value = status;
        return value != null ? value : "";
    }

    public String getSerialNumber() {
        String value = serialNumber;
        return value != null ? value : "";
    }

    public String getManufacturer() {
        String value = manufacturer;
        return value != null ? value : "";
    }

    public String getDeviceTypeName() {
        String value = deviceTypeName;
        return value != null ? value : "";
    }

    public String getDeviceToken() {
        String value = deviceToken;
        return value != null ? value : "";
    }

    public boolean isAirConditioner() {
        return "A/C".equals(deviceTypeName);
    }
}
