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
package org.openhab.binding.electrasmart.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration of the Electra Smart account bridge.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class AccountConfiguration {

    public String phoneNumber = "";
    public String otp = "";
    public int refreshInterval = 30;

    @Override
    public String toString() {
        return "[phoneNumber=" + (phoneNumber.isBlank() ? "<empty>" : "<redacted>") + ", otp="
                + (otp.isBlank() ? "<empty>" : "<redacted>") + ", refreshInterval=" + refreshInterval + "]";
    }
}
