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
package org.openhab.binding.electrasmart.internal;

import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constants shared by the Electra Smart binding.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class ElectraSmartBindingConstants {

    public static final String BINDING_ID = "electrasmart";

    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_AC = new ThingTypeUID(BINDING_ID, "ac");

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_AC);

    // bridge configuration
    public static final String CONFIG_PHONE_NUMBER = "phoneNumber";
    public static final String CONFIG_OTP = "otp";
    public static final String CONFIG_REFRESH_INTERVAL = "refreshInterval";

    // thing configuration
    public static final String CONFIG_DEVICE_ID = "deviceId";

    // channels
    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_OPERATIONAL_MODE = "operational-mode";
    public static final String CHANNEL_TARGET_TEMPERATURE = "target-temperature";
    public static final String CHANNEL_INDOOR_TEMPERATURE = "indoor-temperature";
    public static final String CHANNEL_FAN_SPEED = "fan-speed";
    public static final String CHANNEL_SWING_MODE = "swing-mode";
    public static final String CHANNEL_SHABAT_MODE = "shabat-mode";
    public static final String CHANNEL_TURBO_MODE = "turbo-mode";
    public static final String CHANNEL_SLEEP_FUNCTION = "sleep-function";
    public static final String CHANNEL_IFEEL = "ifeel";
    public static final String CHANNEL_LAST_CONTACT = "last-contact";

    // thing properties
    public static final String PROPERTY_MAC = "mac";
    public static final String PROPERTY_MODEL = "model";
    public static final String PROPERTY_MANUFACTURER = "manufacturer";

    // values used by the Electra API
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_COOL = "COOL";
    public static final String MODE_DRY = "DRY";
    public static final String MODE_FAN = "FAN";
    public static final String MODE_HEAT = "HEAT";
    public static final String MODE_STANDBY = "STBY";

    public static final String FAN_AUTO = "AUTO";
    public static final String FAN_LOW = "LOW";
    public static final String FAN_MEDIUM = "MED";
    public static final String FAN_HIGH = "HIGH";

    public static final String SWING_OFF = "OFF";
    public static final String SWING_VERTICAL = "VERTICAL";
    public static final String SWING_HORIZONTAL = "HORIZONTAL";
    public static final String SWING_BOTH = "BOTH";

    public static final List<String> OPERATIONAL_MODES = List.of(MODE_AUTO, MODE_COOL, MODE_DRY, MODE_FAN, MODE_HEAT);
    public static final List<String> FAN_SPEEDS = List.of(FAN_AUTO, FAN_LOW, FAN_MEDIUM, FAN_HIGH);
    public static final List<String> SWING_MODES = List.of(SWING_OFF, SWING_VERTICAL, SWING_HORIZONTAL, SWING_BOTH);

    public static final int MIN_TEMPERATURE = 17;
    public static final int MAX_TEMPERATURE = 30;

    public static final int UNAVAILABLE_THRESHOLD_SECONDS = 120;
    public static final int API_SETTLE_SECONDS = 5;
    public static final int MIN_REFRESH_INTERVAL_SECONDS = 30;
    public static final int CONSECUTIVE_FAILURE_THRESHOLD = 4;

    private ElectraSmartBindingConstants() {
    }
}
