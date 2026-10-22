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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

/**
 * The response envelope used by every Electra cloud command.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class CommandResponse {

    private int status;
    private @Nullable String desc;
    private @Nullable Data data;

    public int getStatus() {
        return status;
    }

    public String getDesc() {
        String value = desc;
        return value != null ? value : "";
    }

    public @Nullable Data getData() {
        return data;
    }

    /**
     * @return {@code true} when the transport-level status reports success
     */
    public boolean isSuccess() {
        return status == 0;
    }

    /**
     * @return {@code true} when both the transport and the command result report success
     */
    public boolean isDataSuccess() {
        Data payload = data;
        return payload != null && payload.getRes() != null && payload.getRes() == 0;
    }

    public static class Data {

        private @Nullable Integer res;

        @SerializedName("res_desc")
        private @Nullable String resDesc;

        private @Nullable String sid;
        private @Nullable String token;
        private @Nullable List<DeviceRecord> devices;
        private @Nullable Integer timeDelta;
        private @Nullable CommandJson commandJson;

        public @Nullable Integer getRes() {
            return res;
        }

        public String getResDesc() {
            String value = resDesc;
            return value != null ? value : "";
        }

        public String getSid() {
            String value = sid;
            return value != null ? value : "";
        }

        public String getToken() {
            String value = token;
            return value != null ? value : "";
        }

        public @Nullable List<DeviceRecord> getDevices() {
            return devices;
        }

        public @Nullable Integer getTimeDelta() {
            return timeDelta;
        }

        public @Nullable CommandJson getCommandJson() {
            return commandJson;
        }
    }
}
