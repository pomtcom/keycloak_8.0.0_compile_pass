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

package org.keycloak.testsuite.model;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.common.util.Time;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.ModelTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 * @author <a href="mailto:mabartos@redhat.com">Martin Bartos</a>
 */
public class UserSessionInitializerTest extends AbstractTestRealmKeycloakTest {
    private final String realmName = "test";

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, org.keycloak.testsuite.model.UserSessionInitializerTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.model");
    }

    @Before
    public void before() {
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealm("test");
            session.users().addUser(realm, "user1").setEmail("user1@localhost");
            session.users().addUser(realm, "user2").setEmail("user2@localhost");
        });
    }

    @After
    public void after() {
        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName("test");
            session.sessions().removeUserSessions(realm);

            UserModel user1 = session.users().getUserByUsername("user1", realm);
            UserModel user2 = session.users().getUserByUsername("user2", realm);

            UserManager um = new UserManager(session);
            if (user1 != null)
                um.removeUser(realm, user1);
            if (user2 != null)
                um.removeUser(realm, user2);
        });
    }

    @Test
    @ModelTest
    public void testUserSessionInitializer(KeycloakSession session) {
        AtomicReference<Integer> startedAtomic = new AtomicReference<>();
        AtomicReference<UserSessionModel[]> origSessionsAtomic = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession SessionInit1) -> {
            KeycloakSession currentSession = SessionInit1;
            UserSessionManager sessionManager = new UserSessionManager(currentSession);

            int started = Time.currentTime();
            startedAtomic.set(started);

            UserSessionModel[] origSessions = createSessionsInPersisterOnly(currentSession);
            origSessionsAtomic.set(origSessions);

            // Load sessions from persister into infinispan/memory
            UserSessionProviderFactory userSessionFactory = (UserSessionProviderFactory) currentSession.getKeycloakSessionFactory().getProviderFactory(UserSessionProvider.class);
            userSessionFactory.loadPersistentSessions(currentSession.getKeycloakSessionFactory(), 1, 2);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession SessionInit2) -> {
            KeycloakSession currentSession = SessionInit2;
            RealmModel realm = currentSession.realms().getRealmByName(realmName);

            int started = startedAtomic.get();

            UserSessionModel[] origSessions = origSessionsAtomic.get();

            // Assert sessions are in
            ClientModel testApp = realm.getClientByClientId("test-app");
            ClientModel thirdparty = realm.getClientByClientId("third-party");

            assertThat("Count of offline sesions for client 'test-app'", currentSession.sessions().getOfflineSessionsCount(realm, testApp), is((long) 3));
            assertThat("Count of offline sesions for client 'third-party'", currentSession.sessions().getOfflineSessionsCount(realm, thirdparty), is((long) 1));

            List<UserSessionModel> loadedSessions = currentSession.sessions().getOfflineUserSessions(realm, testApp, 0, 10);
            UserSessionProviderTest.assertSessions(loadedSessions, origSessions);

            assertSessionLoaded(loadedSessions, origSessions[0].getId(), currentSession.users().getUserByUsername("user1", realm), "127.0.0.1", started, started, "test-app", "third-party");
            assertSessionLoaded(loadedSessions, origSessions[1].getId(), currentSession.users().getUserByUsername("user1", realm), "127.0.0.2", started, started, "test-app");
            assertSessionLoaded(loadedSessions, origSessions[2].getId(), currentSession.users().getUserByUsername("user2", realm), "127.0.0.3", started, started, "test-app");
        });
    }

    @Test
    @ModelTest
    public void testUserSessionInitializerWithDeletingClient(KeycloakSession session) {
        AtomicReference<Integer> startedAtomic = new AtomicReference<>();
        AtomicReference<UserSessionModel[]> origSessionsAtomic = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession SessionInitWithDeleting1) -> {
            KeycloakSession currentSession = SessionInitWithDeleting1;
            UserSessionManager sessionManager = new UserSessionManager(currentSession);

            RealmModel realm = currentSession.realms().getRealmByName(realmName);

            int started = Time.currentTime();
            startedAtomic.set(started);

            origSessionsAtomic.set(createSessionsInPersisterOnly(currentSession));

            // Delete one of the clients now. Delete it directly in DB just for the purpose of simulating the issue (normally clients should be removed through ClientManager)
            ClientModel testApp = realm.getClientByClientId("test-app");
            realm.removeClient(testApp.getId());
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession SessionInitWithDeleting2) -> {
            KeycloakSession currentSession = SessionInitWithDeleting2;

            // Load sessions from persister into infinispan/memory
            UserSessionProviderFactory userSessionFactory = (UserSessionProviderFactory) currentSession.getKeycloakSessionFactory().getProviderFactory(UserSessionProvider.class);
            userSessionFactory.loadPersistentSessions(currentSession.getKeycloakSessionFactory(), 1, 2);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession SessionInitWithDeleting3) -> {
            KeycloakSession currentSession = SessionInitWithDeleting3;
            RealmModel realm = currentSession.realms().getRealmByName(realmName);

            int started = startedAtomic.get();

            UserSessionModel[] origSessions = origSessionsAtomic.get();

            // Assert sessions are in
            ClientModel thirdparty = realm.getClientByClientId("third-party");

            assertThat("Count of offline sesions for client 'third-party'", currentSession.sessions().getOfflineSessionsCount(realm, thirdparty), is((long) 1));
            List<UserSessionModel> loadedSessions = currentSession.sessions().getOfflineUserSessions(realm, thirdparty, 0, 10);

            assertThat("Size of loaded Sessions", loadedSessions.size(), is(1));
            assertSessionLoaded(loadedSessions, origSessions[0].getId(), currentSession.users().getUserByUsername("user1", realm), "127.0.0.1", started, started, "third-party");

            // Revert client
            realm.addClient("test-app");
        });

    }

    // Create sessions in persister + infinispan, but then delete them from infinispan cache. This is to allow later testing of initializer. Return the list of "origSessions"
    private UserSessionModel[] createSessionsInPersisterOnly(KeycloakSession session) {
        AtomicReference<UserSessionModel[]> origSessionsAtomic = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession createSessionPersister1) -> {
            KeycloakSession currentSession = createSessionPersister1;

            UserSessionModel[] origSessions = createSessions(currentSession);
            origSessionsAtomic.set(origSessions);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession createSessionPersister2) -> {
            KeycloakSession currentSession = createSessionPersister2;
            RealmModel realm = currentSession.realms().getRealmByName(realmName);
            UserSessionManager sessionManager = new UserSessionManager(currentSession);

            UserSessionModel[] origSessions = origSessionsAtomic.get();

            for (UserSessionModel origSession : origSessions) {
                UserSessionModel userSession = currentSession.sessions().getUserSession(realm, origSession.getId());
                for (AuthenticatedClientSessionModel clientSession : userSession.getAuthenticatedClientSessions().values()) {
                    sessionManager.createOrUpdateOfflineSession(clientSession, userSession);
                }
            }
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession createSessionPersister3) -> {
            KeycloakSession currentSession = createSessionPersister3;
            RealmModel realm = currentSession.realms().getRealmByName(realmName);

            // Delete cache (persisted sessions are still kept)
            currentSession.sessions().onRealmRemoved(realm);

            // Clear ispn cache to ensure initializerState is removed as well
            InfinispanConnectionProvider infinispan = currentSession.getProvider(InfinispanConnectionProvider.class);
            infinispan.getCache(InfinispanConnectionProvider.WORK_CACHE_NAME).clear();

        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession createSessionPersister4) -> {
            KeycloakSession currentSession = createSessionPersister4;
            RealmModel realm = currentSession.realms().getRealmByName(realmName);

            ClientModel testApp = realm.getClientByClientId("test-app");
            ClientModel thirdparty = realm.getClientByClientId("third-party");
            assertThat("Count of offline sessions for client 'test-app'", currentSession.sessions().getOfflineSessionsCount(realm, testApp), is((long) 0));
            assertThat("Count of offline sessions for client 'third-party'", currentSession.sessions().getOfflineSessionsCount(realm, thirdparty), is((long) 0));
        });

        return origSessionsAtomic.get();
    }

    private AuthenticatedClientSessionModel createClientSession(KeycloakSession session, ClientModel client, UserSessionModel userSession, String redirect, String state) {
        RealmModel realm = session.realms().getRealmByName(realmName);

        AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(realm, client, userSession);
        clientSession.setRedirectUri(redirect);
        if (state != null)
            clientSession.setNote(OIDCLoginProtocol.STATE_PARAM, state);
        return clientSession;
    }

    private UserSessionModel[] createSessions(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName(realmName);

        UserSessionModel[] sessions = new UserSessionModel[3];
        sessions[0] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", true, null, null);

        createClientSession(session, realm.getClientByClientId("test-app"), sessions[0], "http://redirect", "state");
        createClientSession(session, realm.getClientByClientId("third-party"), sessions[0], "http://redirect", "state");

        sessions[1] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.2", "form", true, null, null);
        createClientSession(session, realm.getClientByClientId("test-app"), sessions[1], "http://redirect", "state");

        sessions[2] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user2", realm), "user2", "127.0.0.3", "form", true, null, null);
        createClientSession(session, realm.getClientByClientId("test-app"), sessions[2], "http://redirect", "state");

        return sessions;
    }

    private void assertSessionLoaded(List<UserSessionModel> sessions, String id, UserModel user, String ipAddress, int started, int lastRefresh, String... clients) {
        for (UserSessionModel session : sessions) {
            if (session.getId().equals(id)) {
                UserSessionProviderTest.assertSession(session, user, ipAddress, started, lastRefresh, clients);
                return;
            }
        }
        Assert.fail("Session with ID " + id + " not found in the list");
    }

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }
}

