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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The parts of the Electra session that have to survive a restart. The token is long-lived, the IMEI identifies this
 * openHAB instance to the cloud and must not change between restarts.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class StoredSession {

    public String phoneNumber = "";
    public String imei = "";
    public String token = "";
    public long lastOtpRequest;
    public long lastSidRequest;

    @Override
    public String toString() {
        return "[phoneNumber=" + (phoneNumber.isBlank() ? "<empty>" : "<redacted>") + ", imei="
                + (imei.isBlank() ? "<empty>" : "<redacted>") + ", token=" + (token.isBlank() ? "<empty>" : "<set>")
                + "]";
    }
}
