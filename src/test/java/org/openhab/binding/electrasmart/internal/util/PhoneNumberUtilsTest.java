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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PhoneNumberUtils}.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
class PhoneNumberUtilsTest {

    @Test
    void keepsLocalNumbersUnchanged() {
        assertEquals("0521234567", PhoneNumberUtils.normalize("0521234567"));
    }

    @Test
    void convertsInternationalPrefix() {
        assertEquals("0521234567", PhoneNumberUtils.normalize("+972521234567"));
        assertEquals("0521234567", PhoneNumberUtils.normalize("972521234567"));
        assertEquals("0521234567", PhoneNumberUtils.normalize("00972521234567"));
    }

    @Test
    void removesSeparators() {
        assertEquals("0521234567", PhoneNumberUtils.normalize("+972 52-123-4567"));
        assertEquals("0521234567", PhoneNumberUtils.normalize("(052) 123 4567"));
    }

    @Test
    void removesLeadingZeroAfterCountryCode() {
        assertEquals("0521234567", PhoneNumberUtils.normalize("+9720521234567"));
    }

    @Test
    void leavesForeignNumbersAlone() {
        assertEquals("+1555123456", PhoneNumberUtils.normalize("+1 555 123 456"));
    }

    @Test
    void handlesBlankInput() {
        assertEquals("", PhoneNumberUtils.normalize(""));
        assertEquals("", PhoneNumberUtils.normalize("   "));
    }
}
