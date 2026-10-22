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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Thing actions used to sign in to the Electra cloud when the configuration form alone is not enough, e.g. after an
 * intruder lockout.
 *
 * @author ElDuderinos - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = ElectraAccountActions.class)
@ThingActionsScope(name = "electrasmart")
@NonNullByDefault
public class ElectraAccountActions implements ThingActions {

    private @Nullable ElectraAccountHandler handler;

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        if (handler instanceof ElectraAccountHandler accountHandler) {
            this.handler = accountHandler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @RuleAction(label = "Request one-time password", description = "Sends a new one-time password by SMS to the phone number of the account")
    public void requestOtp() {
        ElectraAccountHandler accountHandler = handler;
        if (accountHandler != null) {
            accountHandler.requestOtp();
        }
    }

    public static void requestOtp(@Nullable ThingActions actions) {
        if (actions instanceof ElectraAccountActions accountActions) {
            accountActions.requestOtp();
        }
    }

    @RuleAction(label = "Submit one-time password", description = "Signs in with a one-time password received by SMS")
    public void submitOtp(
            @ActionInput(name = "otp", label = "One-time password", type = "java.lang.String", required = true) String otp) {
        ElectraAccountHandler accountHandler = handler;
        if (accountHandler != null) {
            accountHandler.submitOtp(otp);
        }
    }

    public static void submitOtp(@Nullable ThingActions actions, String otp) {
        if (actions instanceof ElectraAccountActions accountActions) {
            accountActions.submitOtp(otp);
        }
    }

    @RuleAction(label = "Sign out", description = "Drops the stored Electra session and starts the sign-in flow again")
    public void signOut() {
        ElectraAccountHandler accountHandler = handler;
        if (accountHandler != null) {
            accountHandler.signOut();
        }
    }

    public static void signOut(@Nullable ThingActions actions) {
        if (actions instanceof ElectraAccountActions accountActions) {
            accountActions.signOut();
        }
    }
}
