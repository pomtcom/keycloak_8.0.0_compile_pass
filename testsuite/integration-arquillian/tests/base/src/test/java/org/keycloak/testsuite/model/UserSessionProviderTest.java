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
import org.keycloak.models.*;
import org.keycloak.models.sessions.infinispan.entities.UserSessionEntity;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.ResetTimeOffsetEvent;
import org.keycloak.models.utils.SessionTimeoutHelper;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.ModelTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;

import java.util.*;

import static org.junit.Assert.*;
import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class UserSessionProviderTest extends AbstractTestRealmKeycloakTest {

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, UserSessionProviderTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.model");
    }


    public static void setupRealm(KeycloakSession session){
        RealmModel realm = session.realms().getRealmByName("test");
        UserModel user1 = session.users().addUser(realm, "user1");
        user1.setEmail("user1@localhost");
        UserModel user2 = session.users().addUser(realm, "user2");
        user2.setEmail("user2@localhost");
    }
    @Before
    public  void before() {
        testingClient.server().run( session -> {
            RealmModel realm = session.realms().getRealmByName("test");
            realm = session.realms().getRealm("test");
            session.users().addUser(realm, "user1").setEmail("user1@localhost");
            session.users().addUser(realm, "user2").setEmail("user2@localhost");
        });
    }

    @After
    public void after() {
        testingClient.server().run( session -> {
            RealmModel realm = session.realms().getRealmByName("test");
            session.sessions().removeUserSessions(realm);
            UserModel user1 = session.users().getUserByUsername("user1", realm);
            UserModel user2 = session.users().getUserByUsername("user2", realm);

            UserManager um = new UserManager(session);
            if (user1 != null) {
                um.removeUser(realm, user1);
            }
            if (user2 != null) {
                um.removeUser(realm, user2);
            }
        });
    }

    @Test
    @ModelTest
    public  void testCreateSessions(KeycloakSession session) {
        int started = Time.currentTime();
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        assertSession(session.sessions().getUserSession(realm, sessions[0].getId()), session.users().getUserByUsername("user1", realm), "127.0.0.1", started, started, "test-app", "third-party");
        assertSession(session.sessions().getUserSession(realm, sessions[1].getId()), session.users().getUserByUsername("user1", realm), "127.0.0.2", started, started, "test-app");
        assertSession(session.sessions().getUserSession(realm, sessions[2].getId()), session.users().getUserByUsername("user2", realm), "127.0.0.3", started, started, "test-app");
    }

    @Test
    @ModelTest
    public void testUpdateSession(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);
        session.sessions().getUserSession(realm, sessions[0].getId()).setLastSessionRefresh(1000);

        assertEquals(1000, session.sessions().getUserSession(realm, sessions[0].getId()).getLastSessionRefresh());
    }

    @Test
    @ModelTest
    public void testUpdateSessionInSameTransaction(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);
        session.sessions().getUserSession(realm, sessions[0].getId()).setLastSessionRefresh(1000);
        assertEquals(1000, session.sessions().getUserSession(realm, sessions[0].getId()).getLastSessionRefresh());
    }

    @Test
    @ModelTest
    public void testRestartSession(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        int started = Time.currentTime();
        UserSessionModel[] sessions = createSessions(session);

        Time.setOffset(100);

        UserSessionModel userSession = session.sessions().getUserSession(realm, sessions[0].getId());
        assertSession(userSession, session.users().getUserByUsername("user1", realm), "127.0.0.1", started, started, "test-app", "third-party");

        userSession.restartSession(realm, session.users().getUserByUsername("user2", realm), "user2", "127.0.0.6", "form", true, null, null);

        userSession = session.sessions().getUserSession(realm, sessions[0].getId());
        assertSession(userSession, session.users().getUserByUsername("user2", realm), "127.0.0.6", started + 100, started + 100);

        Time.setOffset(0);
    }

    @Test
    @ModelTest
    public void testCreateClientSession(KeycloakSession session) {

        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        Map<String, AuthenticatedClientSessionModel> clientSessions = session.sessions().getUserSession(realm, sessions[0].getId()).getAuthenticatedClientSessions();
        assertEquals(2, clientSessions.size());

        String clientUUID = realm.getClientByClientId("test-app").getId();

        AuthenticatedClientSessionModel session1 = clientSessions.get(clientUUID);

        assertNull(session1.getAction());
        assertEquals(realm.getClientByClientId("test-app").getClientId(), session1.getClient().getClientId());
        assertEquals(sessions[0].getId(), session1.getUserSession().getId());
        assertEquals("http://redirect", session1.getRedirectUri());
        assertEquals("state", session1.getNote(OIDCLoginProtocol.STATE_PARAM));
    }

    @Test
    @ModelTest
    public void testUpdateClientSession(KeycloakSession session) {

        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        String userSessionId = sessions[0].getId();
        String clientUUID = realm.getClientByClientId("test-app").getId();

        UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);
        AuthenticatedClientSessionModel clientSession = userSession.getAuthenticatedClientSessions().get(clientUUID);

        int time = clientSession.getTimestamp();
        assertNull(clientSession.getAction());

        clientSession.setAction(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name());
        clientSession.setTimestamp(time + 10);

        AuthenticatedClientSessionModel updated = session.sessions().getUserSession(realm, userSessionId).getAuthenticatedClientSessions().get(clientUUID);
        assertEquals(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name(), updated.getAction());
        assertEquals(time + 10, updated.getTimestamp());
    }

    @Test
    @ModelTest
    public void testUpdateClientSessionWithGetByClientId(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        String userSessionId = sessions[0].getId();
        String clientUUID = realm.getClientByClientId("test-app").getId();

        UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);
        AuthenticatedClientSessionModel clientSession = userSession.getAuthenticatedClientSessionByClient(clientUUID);

        int time = clientSession.getTimestamp();
        assertNull(clientSession.getAction());

        clientSession.setAction(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name());
        clientSession.setTimestamp(time + 10);

        AuthenticatedClientSessionModel updated = session.sessions().getUserSession(realm, userSessionId).getAuthenticatedClientSessionByClient(clientUUID);
        assertEquals(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name(), updated.getAction());
        assertEquals(time + 10, updated.getTimestamp());
    }

    @Test
    @ModelTest
    public void testUpdateClientSessionInSameTransaction(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        String userSessionId = sessions[0].getId();
        String clientUUID = realm.getClientByClientId("test-app").getId();

        UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);
        AuthenticatedClientSessionModel clientSession = userSession.getAuthenticatedClientSessionByClient(clientUUID);

        clientSession.setAction(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name());
        clientSession.setNote("foo", "bar");

        AuthenticatedClientSessionModel updated = session.sessions().getUserSession(realm, userSessionId).getAuthenticatedClientSessionByClient(clientUUID);
        assertEquals(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name(), updated.getAction());
        assertEquals("bar", updated.getNote("foo"));
    }

    @Test
    @ModelTest
    public void testGetUserSessions(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        KeycloakTransaction transaction = session.getTransactionManager();
        if (!transaction.getRollbackOnly()) {
            transaction.commit();

        }


        assertSessions(session.sessions().getUserSessions(realm, session.users().getUserByUsername("user1", realm)), sessions[0], sessions[1]);
        assertSessions(session.sessions().getUserSessions(realm, session.users().getUserByUsername("user2", realm)), sessions[2]);
    }

    @Test
    @ModelTest
    public void testRemoveUserSessionsByUser(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        Map<String, Integer> clientSessionsKept = new HashMap<>();
        for (UserSessionModel s : sessions) {
            s = session.sessions().getUserSession(realm, s.getId());

            if (!s.getUser().getUsername().equals("user1")) {
                clientSessionsKept.put(s.getId(),  s.getAuthenticatedClientSessions().keySet().size());
            }
        }

        session.sessions().removeUserSessions(realm, session.users().getUserByUsername("user1", realm));

        assertTrue(session.sessions().getUserSessions(realm, session.users().getUserByUsername("user1", realm)).isEmpty());
        List<UserSessionModel> userSessions = session.sessions().getUserSessions(realm, session.users().getUserByUsername("user2", realm));

        assertSame(userSessions.size(), 0);

        session.getTransactionManager().commit();
        // Null test removed, it seems that NULL is not a valid state under the new testsuite so we are testing for Size=0

        for (UserSessionModel userSession : userSessions) {
            Assert.assertEquals((int) clientSessionsKept.get(userSession.getId()), userSession.getAuthenticatedClientSessions().size());
        }
    }

    @Test
    @ModelTest
    public void testRemoveUserSession(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel userSession = createSessions(session)[0];

        session.sessions().removeUserSession(realm, userSession);

        assertNull(session.sessions().getUserSession(realm, userSession.getId()));
    }

    @Test
    @ModelTest
    public  void testRemoveUserSessionsByRealm(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        session.sessions().removeUserSessions(realm);

        assertTrue(session.sessions().getUserSessions(realm, session.users().getUserByUsername("user1", realm)).isEmpty());
        assertTrue(session.sessions().getUserSessions(realm, session.users().getUserByUsername("user2", realm)).isEmpty());
    }

    @Test
    @ModelTest
    public void testOnClientRemoved(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        String thirdPartyClientUUID = realm.getClientByClientId("third-party").getId();

        Map<String, Set<String>> clientSessionsKept = new HashMap<>();
        for (UserSessionModel s : sessions) {
            Set<String> clientUUIDS = new HashSet<>(s.getAuthenticatedClientSessions().keySet());
            clientUUIDS.remove(thirdPartyClientUUID); // This client will be later removed, hence his clientSessions too
            clientSessionsKept.put(s.getId(), clientUUIDS);
        }

        realm.removeClient(thirdPartyClientUUID);

        for (UserSessionModel s : sessions) {
            s = session.sessions().getUserSession(realm, s.getId());
            Set<String> clientUUIDS = s.getAuthenticatedClientSessions().keySet();
            assertEquals(clientUUIDS, clientSessionsKept.get(s.getId()));
        }

        // Revert client
        realm.addClient("third-party");
    }

    @Test
    @ModelTest
    public  void testRemoveUserSessionsByExpired(KeycloakSession session) {
        try {
            RealmModel realm = session.realms().getRealmByName("test");
            ClientModel client = realm.getClientByClientId("test-app");

            Set<String> validUserSessions = new HashSet<>();
            Set<String> validClientSessions = new HashSet<>();
            Set<String> expiredUserSessions = new HashSet<>();

            // create an user session that is older than the max lifespan timeout.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> {
                Time.setOffset(-(realm.getSsoSessionMaxLifespan() + 1));
                UserSessionModel userSession = session1.sessions().createUserSession(realm, session1.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", false, null, null);
                expiredUserSessions.add(userSession.getId());
                AuthenticatedClientSessionModel clientSession = session1.sessions().createClientSession(realm, client, userSession);
                assertEquals(userSession, clientSession.getUserSession());
            });

            // create an user session whose last refresh exceeds the max session idle timeout.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> {
                Time.setOffset(-(realm.getSsoSessionIdleTimeout() + SessionTimeoutHelper.PERIODIC_CLEANER_IDLE_TIMEOUT_WINDOW_SECONDS + 1));
                UserSessionModel s = session1.sessions().createUserSession(realm, session1.users().getUserByUsername("user2", realm), "user2", "127.0.0.1", "form", false, null, null);
                // no need to explicitly set the last refresh time - it is the same as the creation time.
                expiredUserSessions.add(s.getId());
            });

            // create an user session and associated client session that conforms to the max lifespan and max idle timeouts.
            Time.setOffset(0);
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> {
                UserSessionModel userSession = session1.sessions().createUserSession(realm, session1.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", false, null, null);
                validUserSessions.add(userSession.getId());
                validClientSessions.add(session1.sessions().createClientSession(realm, client, userSession).getId());
            });

            // remove the expired sessions - we expect the first two sessions to have been removed as they either expired the max lifespan or the session idle timeouts.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> session1.sessions().removeExpired(realm));

            for (String e : expiredUserSessions) {
                assertNull(session.sessions().getUserSession(realm, e));
            }

            for (String v : validUserSessions) {
                UserSessionModel userSessionLoaded = session.sessions().getUserSession(realm, v);
                assertNotNull(userSessionLoaded);
                // the only valid user session should also have a valid client session that hasn't expired.
                AuthenticatedClientSessionModel clientSessionModel = userSessionLoaded.getAuthenticatedClientSessions().get(client.getId());
                assertNotNull(clientSessionModel);
                assertTrue(validClientSessions.contains(clientSessionModel.getId()));
            }
        } finally {
            Time.setOffset(0);
            session.getKeycloakSessionFactory().publish(new ResetTimeOffsetEvent());
        }
    }

    /**
     * Tests the removal of expired sessions with remember-me enabled. It differs from the non remember me scenario by
     * taking into consideration the specific remember-me timeout values.
     *
     * @param session the {@code KeycloakSession}
     */
    @Test
    @ModelTest
    public  void testRemoveUserSessionsByExpiredRememberMe(KeycloakSession session) {
        RealmModel testRealm = session.realms().getRealmByName("test");
        int previousMaxLifespan = testRealm.getSsoSessionMaxLifespanRememberMe();
        int previousMaxIdle = testRealm.getSsoSessionIdleTimeoutRememberMe();
        try {
            ClientModel client = testRealm.getClientByClientId("test-app");
            Set<String> validUserSessions = new HashSet<>();
            Set<String> validClientSessions = new HashSet<>();
            Set<String> expiredUserSessions = new HashSet<>();

            // first lets update the realm by setting remember-me timeout values, which will be 4 times higher than the default timeout values.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession kcSession) -> {
                RealmModel r = kcSession.realms().getRealmByName("test");
                r.setSsoSessionMaxLifespanRememberMe(r.getSsoSessionMaxLifespan() * 4);
                r.setSsoSessionIdleTimeoutRememberMe(r.getSsoSessionIdleTimeout() * 4);
            });
            // update the realm reference so that the remember-me timeouts are now visible.
            RealmModel realm = session.realms().getRealmByName("test");

            // create an user session with remember-me enabled that is older than the default 'max lifespan' timeout but not older than the 'max lifespan remember-me' timeout.
            // the session's last refresh also exceeds the default 'session idle' timeout but doesn't exceed the 'session idle remember-me' timeout.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession kcSession) -> {
                Time.setOffset(-(realm.getSsoSessionMaxLifespan() * 2));
                UserSessionModel userSession = kcSession.sessions().createUserSession(realm, kcSession.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", true, null, null);
                AuthenticatedClientSessionModel clientSession = kcSession.sessions().createClientSession(realm, client, userSession);
                assertEquals(userSession, clientSession.getUserSession());
                Time.setOffset(-(realm.getSsoSessionIdleTimeout() * 2));
                userSession.setLastSessionRefresh(Time.currentTime());
                validUserSessions.add(userSession.getId());
                validClientSessions.add(clientSession.getId());
            });

            // create an user session with remember-me enabled that is older than the 'max lifespan remember-me' timeout.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession kcSession) -> {
                Time.setOffset(-(realm.getSsoSessionMaxLifespanRememberMe() + 1));
                UserSessionModel userSession = kcSession.sessions().createUserSession(realm, kcSession.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", true, null, null);
                expiredUserSessions.add(userSession.getId());
            });

            // finally create an user session with remember-me enabled whose last refresh exceeds the 'session idle remember-me' timeout.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession kcSession) -> {
                Time.setOffset(-(realm.getSsoSessionIdleTimeoutRememberMe() + SessionTimeoutHelper.PERIODIC_CLEANER_IDLE_TIMEOUT_WINDOW_SECONDS + 1));
                UserSessionModel userSession = kcSession.sessions().createUserSession(realm, kcSession.users().getUserByUsername("user2", realm), "user2", "127.0.0.1", "form", true, null, null);
                // no need to explicitly set the last refresh time - it is the same as the creation time.
                expiredUserSessions.add(userSession.getId());
            });

            // remove the expired sessions - the first session should not be removed as it doesn't exceed any of the remember-me timeout values.
            Time.setOffset(0);
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession kcSession) -> kcSession.sessions().removeExpired(realm));

            for (String sessionId : expiredUserSessions) {
                assertNull(session.sessions().getUserSession(realm, sessionId));
            }

            for (String sessionId : validUserSessions) {
                UserSessionModel userSessionLoaded = session.sessions().getUserSession(realm, sessionId);
                assertNotNull(userSessionLoaded);
                // the only valid user session should also have a valid client session that hasn't expired.
                AuthenticatedClientSessionModel clientSessionModel = userSessionLoaded.getAuthenticatedClientSessions().get(client.getId());
                assertNotNull(clientSessionModel);
                assertTrue(validClientSessions.contains(clientSessionModel.getId()));
            }

        } finally {
            Time.setOffset(0);
            session.getKeycloakSessionFactory().publish(new ResetTimeOffsetEvent());
            // restore the original remember-me timeout values in the realm.
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession kcSession) -> {
                RealmModel r = kcSession.realms().getRealmByName("test");
                r.setSsoSessionMaxLifespanRememberMe(previousMaxLifespan);
                r.setSsoSessionIdleTimeoutRememberMe(previousMaxIdle);
            });
        }
    }

    // KEYCLOAK-2508
    @Test
    @ModelTest
     public void testRemovingExpiredSession(KeycloakSession session) {
        UserSessionModel[] sessions = createSessions(session);
        try {
            Time.setOffset(3600000);
            UserSessionModel userSession = sessions[0];
            RealmModel realm = userSession.getRealm();
            session.sessions().removeExpired(realm);

            // Assert no exception is thrown here
            session.sessions().removeUserSession(realm, userSession);
        } finally {
            Time.setOffset(0);
            session.getKeycloakSessionFactory().publish(new ResetTimeOffsetEvent());
        }
    }

    @Test
    @ModelTest
    public void testGetByClient(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel[] sessions = createSessions(session);

        KeycloakTransaction transaction = session.getTransactionManager();
        if (!transaction.getRollbackOnly()) {
            transaction.commit();
        }


        assertSessions(session.sessions().getUserSessions(realm, realm.getClientByClientId("test-app")), sessions[0], sessions[1], sessions[2]);
        assertSessions(session.sessions().getUserSessions(realm, realm.getClientByClientId("third-party")), sessions[0]);
    }

    @Test
    @ModelTest
    public void testGetByClientPaginated(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        try {
            for (int i = 0; i < 25; i++) {
                Time.setOffset(i);
                UserSessionModel userSession = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0." + i, "form", false, null, null);
                AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(realm, realm.getClientByClientId("test-app"), userSession);
                assertNotNull(clientSession);
                clientSession.setRedirectUri("http://redirect");
                clientSession.setNote(OIDCLoginProtocol.STATE_PARAM, "state");
                clientSession.setTimestamp(userSession.getStarted());
                userSession.setLastSessionRefresh(userSession.getStarted());
            }
        } finally {
            Time.setOffset(0);
        }

        KeycloakTransaction transaction = session.getTransactionManager();
        if (!transaction.getRollbackOnly()) {
            transaction.commit();
        }

        assertPaginatedSession(session, realm, realm.getClientByClientId("test-app"), 0, 1, 1);
        assertPaginatedSession(session, realm, realm.getClientByClientId("test-app"), 0, 10, 10);
        assertPaginatedSession(session, realm, realm.getClientByClientId("test-app"), 10, 10, 10);
        assertPaginatedSession(session, realm, realm.getClientByClientId("test-app"), 20, 10, 5);
        assertPaginatedSession(session, realm, realm.getClientByClientId("test-app"), 30, 10, 0);
    }

    @Test
    @ModelTest
    public void testCreateAndGetInSameTransaction(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        ClientModel client = realm.getClientByClientId("test-app");
        UserSessionModel userSession = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.2", "form", true, null, null);
        AuthenticatedClientSessionModel clientSession = createClientSession(session, client, userSession, "http://redirect", "state");

        UserSessionModel userSessionLoaded = session.sessions().getUserSession(realm, userSession.getId());
        AuthenticatedClientSessionModel clientSessionLoaded = userSessionLoaded.getAuthenticatedClientSessions().get(client.getId());
        Assert.assertNotNull(userSessionLoaded);
        Assert.assertNotNull(clientSessionLoaded);

        Assert.assertEquals(userSession.getId(), clientSessionLoaded.getUserSession().getId());
        Assert.assertEquals(1, userSessionLoaded.getAuthenticatedClientSessions().size());
    }

    @Test
    @ModelTest
    public void testAuthenticatedClientSessions(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserSessionModel userSession = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.2", "form", true, null, null);

        ClientModel client1 = realm.getClientByClientId("test-app");
        ClientModel client2 = realm.getClientByClientId("third-party");

        // Create client1 session
        AuthenticatedClientSessionModel clientSession1 = session.sessions().createClientSession(realm, client1, userSession);
        clientSession1.setAction("foo1");
        clientSession1.setTimestamp(100);

        // Create client2 session
        AuthenticatedClientSessionModel clientSession2 = session.sessions().createClientSession(realm, client2, userSession);
        clientSession2.setAction("foo2");
        clientSession2.setTimestamp(200);

        // Ensure sessions are here
        userSession = session.sessions().getUserSession(realm, userSession.getId());
        Map<String, AuthenticatedClientSessionModel> clientSessions = userSession.getAuthenticatedClientSessions();
        Assert.assertEquals(2, clientSessions.size());
        testAuthenticatedClientSession(clientSessions.get(client1.getId()), "test-app", userSession.getId(), "foo1", 100);
        testAuthenticatedClientSession(clientSessions.get(client2.getId()), "third-party", userSession.getId(), "foo2", 200);

        // Update session1
        clientSessions.get(client1.getId()).setAction("foo1-updated");


        // Ensure updated
        userSession = session.sessions().getUserSession(realm, userSession.getId());
        clientSessions = userSession.getAuthenticatedClientSessions();
        testAuthenticatedClientSession(clientSessions.get(client1.getId()), "test-app", userSession.getId(), "foo1-updated", 100);

        // Rewrite session2
        clientSession2 = session.sessions().createClientSession(realm, client2, userSession);
        clientSession2.setAction("foo2-rewrited");
        clientSession2.setTimestamp(300);


        // Ensure updated
        userSession = session.sessions().getUserSession(realm, userSession.getId());
        clientSessions = userSession.getAuthenticatedClientSessions();
        Assert.assertEquals(2, clientSessions.size());
        testAuthenticatedClientSession(clientSessions.get(client1.getId()), "test-app", userSession.getId(), "foo1-updated", 100);
        testAuthenticatedClientSession(clientSessions.get(client2.getId()), "third-party", userSession.getId(), "foo2-rewrited", 300);

        // remove session
        clientSession1 = userSession.getAuthenticatedClientSessions().get(client1.getId());
        clientSession1.detachFromUserSession();

        userSession = session.sessions().getUserSession(realm, userSession.getId());
        clientSessions = userSession.getAuthenticatedClientSessions();
        Assert.assertEquals(1, clientSessions.size());
        Assert.assertNull(clientSessions.get(client1.getId()));
    }


    private static void testAuthenticatedClientSession(AuthenticatedClientSessionModel clientSession, String expectedClientId, String expectedUserSessionId, String expectedAction, int expectedTimestamp) {
        Assert.assertEquals(expectedClientId, clientSession.getClient().getClientId());
        Assert.assertEquals(expectedUserSessionId, clientSession.getUserSession().getId());
        Assert.assertEquals(expectedAction, clientSession.getAction());
        Assert.assertEquals(expectedTimestamp, clientSession.getTimestamp());
    }

    private static void assertPaginatedSession(KeycloakSession session, RealmModel realm, ClientModel client, int start, int max, int expectedSize) {
        List<UserSessionModel> sessions = session.sessions().getUserSessions(realm, client, start, max);
        String[] actualIps = new String[sessions.size()];

        for (int i = 0; i < actualIps.length; i++) {
            actualIps[i] = sessions.get(i).getIpAddress();
        }

        String[] expectedIps = new String[expectedSize];
        for (int i = 0; i < expectedSize; i++) {
            expectedIps[i] = "127.0.0." + (i + start);
        }

        assertArrayEquals(expectedIps, actualIps);
    }

    @Test
    public void testGetCountByClient() {
        testingClient.server().run(UserSessionProviderTest::testGetCountByClient);
    }
    public static void testGetCountByClient(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        createSessions(session);

        KeycloakTransaction transaction = session.getTransactionManager();
        if (!transaction.getRollbackOnly()) {
            transaction.commit();
        }

        assertEquals(3, session.sessions().getActiveUserSessions(realm, realm.getClientByClientId("test-app")));
        assertEquals(1, session.sessions().getActiveUserSessions(realm, realm.getClientByClientId("third-party")));
    }

    @Test
    public void loginFailures() {
        testingClient.server().run(UserSessionProviderTest::loginFailures);
    }
    public static void loginFailures(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
        UserLoginFailureModel failure1 = session.sessions().addUserLoginFailure(realm, "user1");
        failure1.incrementFailures();

        UserLoginFailureModel failure2 = session.sessions().addUserLoginFailure(realm, "user2");
        failure2.incrementFailures();
        failure2.incrementFailures();

        session.getTransactionManager().commit();

        failure1 = session.sessions().getUserLoginFailure(realm, "user1");
        assertEquals(1, failure1.getNumFailures());

        failure2 = session.sessions().getUserLoginFailure(realm, "user2");
        assertEquals(2, failure2.getNumFailures());

        //session.getTransactionManager().commit();

        // Add the failure, which already exists
        //failure1 = session.sessions().addUserLoginFailure(realm, "user1");
        failure1.incrementFailures();

        //failure1 = session.sessions().getUserLoginFailure(realm, "user1");
        assertEquals(2, failure1.getNumFailures());

        failure1 = session.sessions().getUserLoginFailure(realm, "user1");
        failure1.clearFailures();

        session.getTransactionManager().commit();

        failure1 = session.sessions().getUserLoginFailure(realm, "user1");
        assertEquals(0, failure1.getNumFailures());

        session.sessions().removeUserLoginFailure(realm, "user1");
        session.sessions().removeUserLoginFailure(realm, "user2");

        assertNull(session.sessions().getUserLoginFailure(realm, "user1"));

        session.sessions().removeAllUserLoginFailures(realm);
        assertNull(session.sessions().getUserLoginFailure(realm, "user2"));
    }

    @Test
    public void testOnUserRemoved() {
        testingClient.server().run(UserSessionProviderTest::testOnUserRemoved);
    }
    public static void testOnUserRemoved(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");

        UserModel user1 = session.users().getUserByUsername("user1", realm);
        UserModel user2 = session.users().getUserByUsername("user2", realm);

        UserSessionModel[] sessions = new UserSessionModel[3];
        sessions[0] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", true, null, null);

        createClientSession(session, realm.getClientByClientId("test-app"), sessions[0], "http://redirect", "state");
        createClientSession(session, realm.getClientByClientId("third-party"), sessions[0], "http://redirect", "state");

        sessions[1] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.2", "form", true, null, null);
        createClientSession(session, realm.getClientByClientId("test-app"), sessions[1], "http://redirect", "state");

        sessions[2] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user2", realm), "user2", "127.0.0.3", "form", true, null, null);
        //createClientSession(session, realm.getClientByClientId("test-app"), sessions[2], "http://redirect", "state");
        AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(realm, realm.getClientByClientId("test-app"), sessions[2]);
        clientSession.setRedirectUri("http://redirct");
        clientSession.setNote(OIDCLoginProtocol.STATE_PARAM, "state");


        session.sessions().addUserLoginFailure(realm, user1.getId());
        session.sessions().addUserLoginFailure(realm, user2.getId());

        session.userStorageManager().removeUser(realm, user1);

        assertTrue(session.sessions().getUserSessions(realm, user1).isEmpty());

        session.getTransactionManager().commit();

        assertFalse(session.sessions().getUserSessions(realm, session.users().getUserByUsername("user2", realm)).isEmpty());

        user1 = session.users().getUserByUsername("user1", realm);
        user2 = session.users().getUserByUsername("user2", realm);

        // it seems as if Null does not happen with the new test suite.  The sizes of these are ZERO so the removes worked at this point.
        //assertNull(session.sessions().getUserLoginFailure(realm, user1.getId()));
        //assertNotNull(session.sessions().getUserLoginFailure(realm, user2.getId()));
    }

    private static AuthenticatedClientSessionModel createClientSession(KeycloakSession session, ClientModel client, UserSessionModel userSession, String redirect, String state) {
        RealmModel realm = session.realms().getRealmByName("test");
        AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(realm, client, userSession);
        clientSession.setRedirectUri(redirect);
        if (state != null) clientSession.setNote(OIDCLoginProtocol.STATE_PARAM, state);
        return clientSession;
    }

    private static UserSessionModel[] createSessions(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName("test");
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

    public static void assertSessions(List<UserSessionModel> actualSessions, UserSessionModel... expectedSessions) {
        String[] expected = new String[expectedSessions.length];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = expectedSessions[i].getId();
        }

        String[] actual = new String[actualSessions.size()];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = actualSessions.get(i).getId();
        }

        Arrays.sort(expected);
        Arrays.sort(actual);

        assertArrayEquals(expected, actual);
    }

    public static  void assertSession(UserSessionModel session, UserModel user, String ipAddress, int started, int lastRefresh, String... clients) {
        assertEquals(user.getId(), session.getUser().getId());
        assertEquals(ipAddress, session.getIpAddress());
        assertEquals(user.getUsername(), session.getLoginUsername());
        assertEquals("form", session.getAuthMethod());
        assertTrue(session.isRememberMe());
        assertTrue(session.getStarted() >= started - 1 && session.getStarted() <= started + 1);
        assertTrue(session.getLastSessionRefresh() >= lastRefresh - 1 && session.getLastSessionRefresh() <= lastRefresh + 1);

        String[] actualClients = new String[session.getAuthenticatedClientSessions().size()];
        int i = 0;
        for (Map.Entry<String, AuthenticatedClientSessionModel> entry : session.getAuthenticatedClientSessions().entrySet()) {
            String clientUUID = entry.getKey();
            AuthenticatedClientSessionModel clientSession = entry.getValue();
            Assert.assertEquals(clientUUID, clientSession.getClient().getId());
            actualClients[i] = clientSession.getClient().getClientId();
            i++;
        }

        Arrays.sort(clients);
        Arrays.sort(actualClients);

        assertArrayEquals(clients, actualClients);
    }

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {

    }
}
