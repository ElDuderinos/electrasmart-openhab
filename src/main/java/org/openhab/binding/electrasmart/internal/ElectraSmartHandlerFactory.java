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

import static org.openhab.binding.electrasmart.internal.ElectraSmartBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.electrasmart.internal.handler.ElectraAcHandler;
import org.openhab.binding.electrasmart.internal.handler.ElectraAccountHandler;
import org.openhab.core.i18n.UnitProvider;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates the handlers of the Electra Smart binding.
 *
 * @author ElDuderinos - Initial contribution
 */
@Component(service = ThingHandlerFactory.class, configurationPid = "binding.electrasmart")
@NonNullByDefault
public class ElectraSmartHandlerFactory extends BaseThingHandlerFactory {

    private final HttpClient httpClient;
    private final UnitProvider unitProvider;
    private final StorageService storageService;

    @Activate
    public ElectraSmartHandlerFactory(@Reference HttpClientFactory httpClientFactory,
            @Reference UnitProvider unitProvider, @Reference StorageService storageService) {
        this.httpClient = httpClientFactory.getCommonHttpClient();
        this.unitProvider = unitProvider;
        this.storageService = storageService;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        if (THING_TYPE_ACCOUNT.equals(thingTypeUID)) {
            return new ElectraAccountHandler((Bridge) thing, httpClient, storageService);
        }
        if (THING_TYPE_AC.equals(thingTypeUID)) {
            return new ElectraAcHandler(thing, unitProvider);
        }
        return null;
    }
}
