/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.forms.login;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.provider.Provider;
import org.keycloak.sessions.AuthenticationSessionModel;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public interface LoginFormsProvider extends Provider {

    String UPDATE_PROFILE_CONTEXT_ATTR = "updateProfileCtx";

    String IDENTITY_PROVIDER_BROKER_CONTEXT = "identityProviderBrokerCtx";

    String USERNAME_EDIT_DISABLED = "usernameEditDisabled";


    /**
     * Adds a script to the html header
     *
     * @param scriptUrl
     */
    void addScript(String scriptUrl);

    Response createResponse(UserModel.RequiredAction action);

    Response createForm(String form);

    String getMessage(String message);

    String getMessage(String message, String... parameters);

    Response createLoginUsernamePassword();

    Response createLoginUsername();

    Response createLoginPassword();

    Response createPasswordReset();

    Response createLoginTotp();

    Response createLoginWebAuthn();

    Response createRegistration();

    Response createInfoPage();

    Response createUpdateProfilePage();

    Response createIdpLinkConfirmLinkPage();

    Response createIdpLinkEmailPage();

    Response createLoginExpiredPage();

    Response createErrorPage(Response.Status status);

    Response createOAuthGrant();

    Response createCode();

    Response createX509ConfirmPage();

    Response createSamlPostForm();

    LoginFormsProvider setAuthenticationSession(AuthenticationSessionModel authenticationSession);

    LoginFormsProvider setClientSessionCode(String accessCode);

    LoginFormsProvider setAccessRequest(List<ClientScopeModel> clientScopesRequested);

    /**
     * Set one global error message.
     * 
     * @param message key of message
     * @param parameters to be formatted into message
     */
    LoginFormsProvider setError(String message, Object ... parameters);
    
    /**
     * Set multiple error messages.
     * 
     * @param messages to be set
     */
    LoginFormsProvider setErrors(List<FormMessage> messages);

    LoginFormsProvider addError(FormMessage errorMessage);

    /**
     * Add a success message to the form
     *
     * @param errorMessage
     * @return
     */
    LoginFormsProvider addSuccess(FormMessage errorMessage);

    LoginFormsProvider setSuccess(String message, Object ... parameters);

    LoginFormsProvider setInfo(String message, Object ... parameters);

    LoginFormsProvider setUser(UserModel user);

    LoginFormsProvider setResponseHeader(String headerName, String headerValue);

    LoginFormsProvider setFormData(MultivaluedMap<String, String> formData);

    LoginFormsProvider setAttribute(String name, Object value);

    LoginFormsProvider setStatus(Response.Status status);

    LoginFormsProvider setMediaType(javax.ws.rs.core.MediaType type);

    LoginFormsProvider setActionUri(URI requestUri);

    LoginFormsProvider setExecution(String execution);

    LoginFormsProvider setAuthContext(AuthenticationFlowContext context);
}
