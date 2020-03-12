/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.authentication.authenticators.browser;

import java.util.HashMap;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.Profile;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.provider.ScriptProviderMetadata;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public final class DeployedScriptAuthenticatorFactory extends ScriptBasedAuthenticatorFactory {

    private final AuthenticatorConfigModel model;

    public DeployedScriptAuthenticatorFactory(ScriptProviderMetadata metadata) {
        model = new AuthenticatorConfigModel();

        model.setId(metadata.getId());
        model.setAlias(metadata.getName());
        model.setConfig(new HashMap<>());
        model.getConfig().put("scriptName", metadata.getName());
        model.getConfig().put("scriptCode", metadata.getCode());
        model.getConfig().put("scriptDescription", metadata.getDescription());
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new ScriptBasedAuthenticator() {
            @Override
            protected AuthenticatorConfigModel getAuthenticatorConfig(AuthenticationFlowContext context) {
                return model;
            }
        };
    }

    @Override
    public String getId() {
        return model.getId();
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getDisplayType() {
        return model.getAlias();
    }

    @Override
    public String getHelpText() {
        return model.getAlias();
    }

    @Override
    public boolean isSupported() {
        return Profile.isFeatureEnabled(Profile.Feature.SCRIPTS);
    }
}
