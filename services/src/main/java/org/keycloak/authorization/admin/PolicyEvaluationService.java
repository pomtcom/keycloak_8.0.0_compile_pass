/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.authorization.admin;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.admin.representation.PolicyEvaluationResponseBuilder;
import org.keycloak.authorization.attribute.Attributes;
import org.keycloak.authorization.common.DefaultEvaluationContext;
import org.keycloak.authorization.common.KeycloakIdentity;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.permission.ResourcePermission;
import org.keycloak.authorization.policy.evaluation.DecisionPermissionCollector;
import org.keycloak.authorization.policy.evaluation.EvaluationContext;
import org.keycloak.authorization.policy.evaluation.Result;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.authorization.util.Permissions;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.authorization.AuthorizationRequest;
import org.keycloak.representations.idm.authorization.Permission;
import org.keycloak.representations.idm.authorization.PolicyEvaluationRequest;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.ScopeRepresentation;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class PolicyEvaluationService {

    private static final Logger logger = Logger.getLogger(PolicyEvaluationService.class);

    private final AuthorizationProvider authorization;
    private final AdminPermissionEvaluator auth;
    private final ResourceServer resourceServer;

    PolicyEvaluationService(ResourceServer resourceServer, AuthorizationProvider authorization, AdminPermissionEvaluator auth) {
        this.resourceServer = resourceServer;
        this.authorization = authorization;
        this.auth = auth;
    }

    @POST
    @Consumes("application/json")
    @Produces("application/json")
    public Response evaluate(PolicyEvaluationRequest evaluationRequest) {
        this.auth.realm().requireViewAuthorization();
        CloseableKeycloakIdentity identity = createIdentity(evaluationRequest);
        try {
            AuthorizationRequest request = new AuthorizationRequest();
            Map<String, List<String>> claims = new HashMap<>();
            Map<String, String> givenAttributes = evaluationRequest.getContext().get("attributes");

            if (givenAttributes != null) {
                givenAttributes.forEach((key, entryValue) -> {
                    if (entryValue != null) {
                        List<String> values = new ArrayList();

                        for (String value : entryValue.split(",")) {
                            values.add(value);
                        }

                        claims.put(key, values);
                    }
                });
            }

            request.setClaims(claims);

            return Response.ok(PolicyEvaluationResponseBuilder.build(evaluate(evaluationRequest, createEvaluationContext(evaluationRequest, identity), request), resourceServer, authorization, identity)).build();
        } catch (Exception e) {
            logger.error("Error while evaluating permissions", e);
            throw new ErrorResponseException(OAuthErrorException.SERVER_ERROR, "Error while evaluating permissions.", Status.INTERNAL_SERVER_ERROR);
        } finally {
            identity.close();
        }
    }

    private EvaluationDecisionCollector evaluate(PolicyEvaluationRequest evaluationRequest, EvaluationContext evaluationContext, AuthorizationRequest request) {
        return authorization.evaluators().from(createPermissions(evaluationRequest, evaluationContext, authorization, request), evaluationContext).evaluate(new EvaluationDecisionCollector(authorization, resourceServer, request));
    }

    private EvaluationContext createEvaluationContext(PolicyEvaluationRequest representation, KeycloakIdentity identity) {
        return new DefaultEvaluationContext(identity, this.authorization.getKeycloakSession()) {
            @Override
            public Attributes getAttributes() {
                Map<String, Collection<String>> attributes = new HashMap<>(super.getAttributes().toMap());
                Map<String, String> givenAttributes = representation.getContext().get("attributes");

                if (givenAttributes != null) {
                    givenAttributes.forEach((key, entryValue) -> {
                        if (entryValue != null) {
                            List<String> values = new ArrayList();

                            for (String value : entryValue.split(",")) {
                                values.add(value);
                            }

                            attributes.put(key, values);
                        }
                    });
                }

                return Attributes.from(attributes);
            }
        };
    }

    private List<ResourcePermission> createPermissions(PolicyEvaluationRequest representation, EvaluationContext evaluationContext, AuthorizationProvider authorization, AuthorizationRequest request) {
        return representation.getResources().stream().flatMap((Function<ResourceRepresentation, Stream<ResourcePermission>>) resource -> {
            StoreFactory storeFactory = authorization.getStoreFactory();
            if (resource == null) {
                resource = new ResourceRepresentation();
            }

            Set<ScopeRepresentation> givenScopes = resource.getScopes();

            if (givenScopes == null) {
                givenScopes = new HashSet();
            }

            ScopeStore scopeStore = storeFactory.getScopeStore();

            Set<Scope> scopes = givenScopes.stream().map(scopeRepresentation -> scopeStore.findByName(scopeRepresentation.getName(), resourceServer.getId())).collect(Collectors.toSet());

            if (resource.getId() != null) {
                Resource resourceModel = storeFactory.getResourceStore().findById(resource.getId(), resourceServer.getId());
                return new ArrayList<>(Arrays.asList(Permissions.createResourcePermissions(resourceModel, scopes, authorization, request))).stream();
            } else if (resource.getType() != null) {
                return storeFactory.getResourceStore().findByType(resource.getType(), resourceServer.getId()).stream().map(resource1 -> Permissions.createResourcePermissions(resource1, scopes, authorization, request));
            } else {
                if (scopes.isEmpty()) {
                    return Permissions.all(resourceServer, evaluationContext.getIdentity(), authorization, request).stream();
                }

                List<Resource> resources = storeFactory.getResourceStore().findByScope(scopes.stream().map(Scope::getId).collect(Collectors.toList()), resourceServer.getId());

                if (resources.isEmpty()) {
                    return scopes.stream().map(scope -> new ResourcePermission(null, new ArrayList<>(Arrays.asList(scope)), resourceServer));
                }


                return resources.stream().map(resource12 -> Permissions.createResourcePermissions(resource12, scopes, authorization, request));
            }
        }).collect(Collectors.toList());
    }

    private static class CloseableKeycloakIdentity extends KeycloakIdentity {
        private UserSessionModel userSession;

        public CloseableKeycloakIdentity(AccessToken accessToken, KeycloakSession keycloakSession, UserSessionModel userSession) {
            super(accessToken, keycloakSession);
            this.userSession = userSession;
        }

        public void close() {
            if (userSession != null) {
                keycloakSession.sessions().removeUserSession(realm, userSession);
            }

        }

        @Override
        public String getId() {
            if (userSession != null) {
                return super.getId();
            }

            String issuedFor = accessToken.getIssuedFor();

            if (issuedFor != null) {
                UserModel serviceAccount = keycloakSession.users().getServiceAccount(realm.getClientByClientId(issuedFor));

                if (serviceAccount != null) {
                    return serviceAccount.getId();
                }
            }

            return null;
        }
    }

    private CloseableKeycloakIdentity createIdentity(PolicyEvaluationRequest representation) {
        KeycloakSession keycloakSession = this.authorization.getKeycloakSession();
        RealmModel realm = keycloakSession.getContext().getRealm();
        AccessToken accessToken = null;


        String subject = representation.getUserId();

        UserSessionModel userSession = null;
        if (subject != null) {
            UserModel userModel = keycloakSession.users().getUserById(subject, realm);

            if (userModel != null) {
                String clientId = representation.getClientId();

                if (clientId == null) {
                    clientId = resourceServer.getId();
                }

                if (clientId != null) {
                    ClientModel clientModel = realm.getClientById(clientId);

                    AuthenticationSessionModel authSession = keycloakSession.authenticationSessions().createRootAuthenticationSession(realm)
                            .createAuthenticationSession(clientModel);
                    authSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
                    authSession.setAuthenticatedUser(userModel);
                    userSession = keycloakSession.sessions().createUserSession(authSession.getParentSession().getId(), realm, userModel, userModel.getUsername(), "127.0.0.1", "passwd", false, null, null);

                    AuthenticationManager.setClientScopesInSession(authSession);
                    ClientSessionContext clientSessionCtx = TokenManager.attachAuthenticationSession(keycloakSession, userSession, authSession);

                    accessToken = new TokenManager().createClientAccessToken(keycloakSession, realm, clientModel, userModel, userSession, clientSessionCtx);
                }
            }
        }

        if (accessToken == null) {
            accessToken = new AccessToken();

            accessToken.subject(representation.getUserId());
            ClientModel client = null;
            String clientId = representation.getClientId();

            if (clientId != null) {
                client = realm.getClientById(clientId);
            }

            if (client == null) {
                client = realm.getClientById(resourceServer.getId());
            }

            accessToken.issuedFor(client.getClientId());
            accessToken.audience(client.getId());
            accessToken.issuer(Urls.realmIssuer(keycloakSession.getContext().getUri().getBaseUri(), realm.getName()));
            accessToken.setRealmAccess(new AccessToken.Access());

        }

        if (representation.getRoleIds() != null && !representation.getRoleIds().isEmpty()) {
            if (accessToken.getRealmAccess() == null) {
                accessToken.setRealmAccess(new AccessToken.Access());
            }
            AccessToken.Access realmAccess = accessToken.getRealmAccess();

            representation.getRoleIds().forEach(realmAccess::addRole);
        }

        return new CloseableKeycloakIdentity(accessToken, keycloakSession, userSession);
    }

    public class EvaluationDecisionCollector extends DecisionPermissionCollector {

        public EvaluationDecisionCollector(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, AuthorizationRequest request) {
            super(authorizationProvider, resourceServer, request);
        }

        @Override
        protected boolean isGranted(Result.PolicyResult policyResult) {
            if (super.isGranted(policyResult)) {
                policyResult.setEffect(Effect.PERMIT);
                return true;
            }
            return false;
        }

        @Override
        protected void grantPermission(AuthorizationProvider authorizationProvider, List<Permission> permissions, ResourcePermission permission, Collection<Scope> grantedScopes, ResourceServer resourceServer, AuthorizationRequest request, Result result) {
            result.setStatus(Effect.PERMIT);
            result.getPermission().getScopes().retainAll(grantedScopes);
            super.grantPermission(authorizationProvider, permissions, permission, grantedScopes, resourceServer, request, result);
        }

        public Collection<Result> getResults() {
            return results.values();
        }
    }
}