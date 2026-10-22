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
package org.openhab.binding.electrasmart.internal.discovery;

import static org.openhab.binding.electrasmart.internal.ElectraSmartBindingConstants.*;

import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.electrasmart.internal.api.ElectraApiException;
import org.openhab.binding.electrasmart.internal.api.dto.DeviceRecord;
import org.openhab.binding.electrasmart.internal.handler.ElectraAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the air conditioners of an authenticated Electra account.
 *
 * @author ElDuderinos - Initial contribution
 */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = ElectraDiscoveryService.class)
public class ElectraDiscoveryService extends AbstractThingHandlerDiscoveryService<ElectraAccountHandler> {

    private static final int DISCOVERY_TIMEOUT_SECONDS = 30;

    private final Logger logger = LoggerFactory.getLogger(ElectraDiscoveryService.class);

    public ElectraDiscoveryService() {
        super(ElectraAccountHandler.class, Set.of(THING_TYPE_AC), DISCOVERY_TIMEOUT_SECONDS, false);
    }

    @Override
    protected void startScan() {
        Thing bridge = thingHandler.getThing();
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            logger.debug("Skipping discovery, the Electra account bridge is not online");
            return;
        }

        try {
            for (DeviceRecord device : thingHandler.discoverDevices()) {
                ThingUID thingUID = new ThingUID(THING_TYPE_AC, bridge.getUID(), normalize(device));
                DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridge.getUID())
                        .withLabel(device.getName().isBlank() ? device.getMac() : device.getName())
                        .withProperty(CONFIG_DEVICE_ID, device.getId()).withProperty(PROPERTY_MAC, device.getMac())
                        .withProperty(PROPERTY_MODEL, device.getModel())
                        .withProperty(PROPERTY_MANUFACTURER, device.getManufacturer())
                        .withRepresentationProperty(PROPERTY_MAC).build();
                thingDiscovered(result);
            }
        } catch (ElectraApiException e) {
            logger.warn("Could not discover Electra air conditioners: {}", e.getMessage());
        }
    }

    private static String normalize(DeviceRecord device) {
        String mac = device.getMac().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!mac.isBlank()) {
            return mac;
        }
        String id = device.getId().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return id.isBlank() ? "device" : id;
    }
}
