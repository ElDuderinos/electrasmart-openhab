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

/**
 * A {@code GET_LAST_TELEMETRY} result.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class Telemetry {

    private int timeDelta = Integer.MAX_VALUE;
    private @Nullable CommandJson commandJson;

    public int getTimeDelta() {
        return timeDelta;
    }

    public void setTimeDelta(int timeDelta) {
        this.timeDelta = timeDelta;
    }

    public @Nullable CommandJson getCommandJson() {
        return commandJson;
    }

    public void setCommandJson(@Nullable CommandJson commandJson) {
        this.commandJson = commandJson;
    }
}
