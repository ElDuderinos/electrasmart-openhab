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
package org.openhab.binding.electrasmart.internal.util;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Normalizes phone numbers for the Electra cloud.
 *
 * <p>
 * The cloud keys an account by the phone number it receives. The Electra Smart app registers the account with the
 * local Israeli format, for example {@code 0521234567}. A number entered with the {@code +972} or {@code 972} prefix
 * reaches the same phone by SMS, but can resolve to a different, empty account, so the international prefix is removed
 * before the number is sent.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public final class PhoneNumberUtils {

    private static final String[] ISRAELI_PREFIXES = { "+972", "00972", "972" };
    private static final int MIN_NATIONAL_NUMBER_LENGTH = 8;

    private PhoneNumberUtils() {
    }

    /**
     * Keeps only digits and a leading plus sign, then converts an Israeli international prefix to the local form.
     *
     * @param phoneNumber the number as entered in the thing configuration
     * @return the number in local format when it carried an Israeli international prefix
     */
    public static String normalize(String phoneNumber) {
        StringBuilder compact = new StringBuilder(phoneNumber.length());
        for (int i = 0; i < phoneNumber.length(); i++) {
            char character = phoneNumber.charAt(i);
            if (Character.isDigit(character)) {
                compact.append(character);
            } else if (character == '+' && compact.length() == 0) {
                compact.append(character);
            }
        }

        String value = compact.toString();
        for (String prefix : ISRAELI_PREFIXES) {
            if (value.startsWith(prefix)) {
                String national = value.substring(prefix.length());
                if (national.startsWith("0")) {
                    national = national.substring(1);
                }
                if (national.length() >= MIN_NATIONAL_NUMBER_LENGTH) {
                    return "0" + national;
                }
            }
        }
        return value;
    }
}
