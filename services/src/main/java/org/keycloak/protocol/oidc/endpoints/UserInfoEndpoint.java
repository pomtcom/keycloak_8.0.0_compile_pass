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
package org.keycloak.protocol.oidc.endpoints;

import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;
import org.keycloak.OAuthErrorException;
import org.keycloak.TokenCategory;
import org.keycloak.TokenVerifier;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.VerificationException;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.SignatureSignerContext;
import org.keycloak.crypto.SignatureVerifierContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.TokenManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCAdvancedConfigWrapper;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.UserSessionCrossDCManager;
import org.keycloak.services.resources.Cors;
import org.keycloak.services.util.DefaultClientSessionContext;
import org.keycloak.services.util.MtlsHoKTokenUtil;
import org.keycloak.utils.MediaType;

import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

/**
 * @author pedroigor
 */
public class UserInfoEndpoint {

    @Context
    private HttpRequest request;

    @Context
    private HttpResponse response;

    @Context
    private KeycloakSession session;

    @Context
    private ClientConnection clientConnection;

    private final org.keycloak.protocol.oidc.TokenManager tokenManager;
    private final AppAuthManager appAuthManager;
    private final RealmModel realm;

    public UserInfoEndpoint(org.keycloak.protocol.oidc.TokenManager tokenManager, RealmModel realm) {
        this.realm = realm;
        this.tokenManager = tokenManager;
        this.appAuthManager = new AppAuthManager();
    }

    @Path("/")
    @OPTIONS
    public Response issueUserInfoPreflight() {
        return Cors.add(this.request, Response.ok()).auth().preflight().build();
    }

    @Path("/")
    @GET
    @NoCache
    public Response issueUserInfoGet(@Context final HttpHeaders headers) {
        String accessToken = this.appAuthManager.extractAuthorizationHeaderToken(headers);
        return issueUserInfo(accessToken);
    }

    @Path("/")
    @POST
    @NoCache
    public Response issueUserInfoPost() {
        // Try header first
        HttpHeaders headers = request.getHttpHeaders();
        String accessToken = this.appAuthManager.extractAuthorizationHeaderToken(headers);

        // Fallback to form parameter
        if (accessToken == null) {
            accessToken = request.getDecodedFormParameters().getFirst("access_token");
        }

        return issueUserInfo(accessToken);
    }

    private Response issueUserInfo(String tokenString) {
        EventBuilder event = new EventBuilder(realm, session, clientConnection)
                .event(EventType.USER_INFO_REQUEST)
                .detail(Details.AUTH_METHOD, Details.VALIDATE_ACCESS_TOKEN);

        if (tokenString == null) {
            event.error(Errors.INVALID_TOKEN);
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Token not provided", Response.Status.BAD_REQUEST);
        }

        AccessToken token;
        try {
            TokenVerifier<AccessToken> verifier = TokenVerifier.create(tokenString, AccessToken.class).withDefaultChecks()
                    .realmUrl(Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName()));

            SignatureVerifierContext verifierContext = session.getProvider(SignatureProvider.class, verifier.getHeader().getAlgorithm().name()).verifier(verifier.getHeader().getKeyId());
            verifier.verifierContext(verifierContext);

            token = verifier.verify().getToken();
        } catch (VerificationException e) {
            event.error(Errors.INVALID_TOKEN);
            throw new ErrorResponseException(OAuthErrorException.INVALID_TOKEN, "Token invalid: " + e.getMessage(), Response.Status.UNAUTHORIZED);
        }

        ClientModel clientModel = realm.getClientByClientId(token.getIssuedFor());
        if (clientModel == null) {
            event.error(Errors.CLIENT_NOT_FOUND);
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Client not found", Response.Status.BAD_REQUEST);
        }

	    if (!clientModel.getProtocol().equals(OIDCLoginProtocol.LOGIN_PROTOCOL)) {
            event.error(Errors.INVALID_CLIENT);
            throw new ErrorResponseException(Errors.INVALID_CLIENT, "Wrong client protocol.", Response.Status.BAD_REQUEST);
        }

        session.getContext().setClient(clientModel);

        event.client(clientModel);

        if (!clientModel.isEnabled()) {
            event.error(Errors.CLIENT_DISABLED);
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Client disabled", Response.Status.BAD_REQUEST);
        }

        UserSessionModel userSession = findValidSession(token, event, clientModel);

        UserModel userModel = userSession.getUser();
        if (userModel == null) {
            event.error(Errors.USER_NOT_FOUND);
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "User not found", Response.Status.BAD_REQUEST);
        }

        event.user(userModel)
                .detail(Details.USERNAME, userModel.getUsername());

        // KEYCLOAK-6771 Certificate Bound Token
        // https://tools.ietf.org/html/draft-ietf-oauth-mtls-08#section-3
        if (OIDCAdvancedConfigWrapper.fromClientModel(clientModel).isUseMtlsHokToken()) {
            if (!MtlsHoKTokenUtil.verifyTokenBindingWithClientCertificate(token, request, session)) {
                event.error(Errors.NOT_ALLOWED);
                throw new ErrorResponseException(OAuthErrorException.UNAUTHORIZED_CLIENT, "Client certificate missing, or its thumbprint and one in the refresh token did NOT match", Response.Status.UNAUTHORIZED);
            }
        }

        // Existence of authenticatedClientSession for our client already handled before
        AuthenticatedClientSessionModel clientSession = userSession.getAuthenticatedClientSessionByClient(clientModel.getId());

        // Retrieve by latest scope parameter
        ClientSessionContext clientSessionCtx = DefaultClientSessionContext.fromClientSessionScopeParameter(clientSession);

        AccessToken userInfo = new AccessToken();
        tokenManager.transformUserInfoAccessToken(session, userInfo, userSession, clientSessionCtx);

        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", userModel.getId());
        claims.putAll(userInfo.getOtherClaims());

        Response.ResponseBuilder responseBuilder;
        OIDCAdvancedConfigWrapper cfg = OIDCAdvancedConfigWrapper.fromClientModel(clientModel);

        if (cfg.isUserInfoSignatureRequired()) {
            String issuerUrl = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName());
            String audience = clientModel.getClientId();
            claims.put("iss", issuerUrl);
            claims.put("aud", audience);

            String signatureAlgorithm = session.tokens().signatureAlgorithm(TokenCategory.USERINFO);

            SignatureProvider signatureProvider = session.getProvider(SignatureProvider.class, signatureAlgorithm);
            SignatureSignerContext signer = signatureProvider.signer();

            String signedUserInfo = new JWSBuilder().type("JWT").jsonContent(claims).sign(signer);

            responseBuilder = Response.ok(signedUserInfo).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JWT);

            event.detail(Details.SIGNATURE_REQUIRED, "true");
            event.detail(Details.SIGNATURE_ALGORITHM, cfg.getUserInfoSignedResponseAlg().toString());
        } else {
            responseBuilder = Response.ok(claims).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);

            event.detail(Details.SIGNATURE_REQUIRED, "false");
        }

        event.success();

        return Cors.add(request, responseBuilder).auth().allowedOrigins(token).build();
    }


    private UserSessionModel findValidSession(AccessToken token, EventBuilder event, ClientModel client) {
        UserSessionModel userSession = new UserSessionCrossDCManager(session).getUserSessionWithClient(realm, token.getSessionState(), false, client.getId());
        UserSessionModel offlineUserSession = null;
        if (AuthenticationManager.isSessionValid(realm, userSession)) {
            checkTokenIssuedAt(token, userSession, event);
            event.session(userSession);
            return userSession;
        } else {
            offlineUserSession = new UserSessionCrossDCManager(session).getUserSessionWithClient(realm, token.getSessionState(), true, client.getId());
            if (AuthenticationManager.isOfflineSessionValid(realm, offlineUserSession)) {
                checkTokenIssuedAt(token, offlineUserSession, event);
                event.session(offlineUserSession);
                return offlineUserSession;
            }
        }

        if (userSession == null && offlineUserSession == null) {
            event.error(Errors.USER_SESSION_NOT_FOUND);
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "User session not found or doesn't have client attached on it", Response.Status.UNAUTHORIZED);
        }

        if (userSession != null) {
            event.session(userSession);
        } else {
            event.session(offlineUserSession);
        }

        event.error(Errors.SESSION_EXPIRED);
        throw new ErrorResponseException(OAuthErrorException.INVALID_TOKEN, "Session expired", Response.Status.UNAUTHORIZED);
    }

    private void checkTokenIssuedAt(AccessToken token, UserSessionModel userSession, EventBuilder event) throws ErrorResponseException {
        if (token.getIssuedAt() + 1 < userSession.getStarted()) {
            event.error(Errors.INVALID_TOKEN);
            throw new ErrorResponseException(OAuthErrorException.INVALID_TOKEN, "Stale token", Response.Status.UNAUTHORIZED);
        }
    }
}
