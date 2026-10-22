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

import com.google.gson.annotations.SerializedName;

/**
 * The {@code OPER} and {@code DIAG_L2} blocks of a telemetry response. The Electra cloud wraps both in JSON encoded
 * strings.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class CommandJson {

    @SerializedName("OPER")
    private @Nullable String oper;

    @SerializedName("DIAG_L2")
    private @Nullable String diagL2;

    public String getOper() {
        String value = oper;
        return value != null ? value : "";
    }

    public String getDiagL2() {
        String value = diagL2;
        return value != null ? value : "";
    }
}
