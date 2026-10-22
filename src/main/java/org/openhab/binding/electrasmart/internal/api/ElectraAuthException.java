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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Thrown when the Electra cloud rejects the configured credentials.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
public class ElectraAuthException extends ElectraApiException {

    private static final long serialVersionUID = 1L;

    public ElectraAuthException(String message) {
        super(message);
    }
}
