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
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.common.util.Time;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.ResetTimeOffsetEvent;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.CommonClientSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.ModelTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;
import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class AuthenticationSessionProviderTest extends AbstractTestRealmKeycloakTest {

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, AuthenticationSessionProviderTest.class)
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
            RealmModel realm = session.realms().getRealm("test");
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
    public void testLoginSessionsCRUD(KeycloakSession session) {
        AtomicReference<String> rootAuthSessionID = new AtomicReference<>();
        AtomicReference<String> tabID = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionCRUD1) -> {
            KeycloakSession currentSession = sessionCRUD1;
            RealmModel realm = currentSession.realms().getRealm("test");

            ClientModel client1 = realm.getClientByClientId("test-app");

            RootAuthenticationSessionModel rootAuthSession = currentSession.authenticationSessions().createRootAuthenticationSession(realm);
            rootAuthSessionID.set(rootAuthSession.getId());

            AuthenticationSessionModel authSession = rootAuthSession.createAuthenticationSession(client1);
            tabID.set(authSession.getTabId());

            authSession.setAction("foo");
            rootAuthSession.setTimestamp(100);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionCRUD2) -> {
            KeycloakSession currentSession = sessionCRUD2;
            RealmModel realm = currentSession.realms().getRealm("test");

            ClientModel client1 = realm.getClientByClientId("test-app");

            // Ensure currentSession is here
            RootAuthenticationSessionModel rootAuthSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, rootAuthSessionID.get());
            AuthenticationSessionModel authSession = rootAuthSession.getAuthenticationSession(client1, tabID.get());
            testAuthenticationSession(authSession, client1.getId(), null, "foo");

            assertThat(rootAuthSession.getTimestamp(), is(100));

            // Update and commit
            authSession.setAction("foo-updated");
            rootAuthSession.setTimestamp(200);
            authSession.setAuthenticatedUser(currentSession.users().getUserByUsername("user1", realm));
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionCRUD3) -> {
            KeycloakSession currentSession = sessionCRUD3;
            RealmModel realm = currentSession.realms().getRealm("test");
            UserModel user1 = currentSession.users().getUserByUsername("user1", realm);

            // Ensure currentSession was updated
            RootAuthenticationSessionModel rootAuthSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, rootAuthSessionID.get());
            ClientModel client1 = realm.getClientByClientId("test-app");
            AuthenticationSessionModel authSession = rootAuthSession.getAuthenticationSession(client1, tabID.get());

            testAuthenticationSession(authSession, client1.getId(), user1.getId(), "foo-updated");

            assertThat(rootAuthSession.getTimestamp(), is(200));

            // Remove and commit
            currentSession.authenticationSessions().removeRootAuthenticationSession(realm, rootAuthSession);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionCRUD4) -> {
            KeycloakSession currentSession = sessionCRUD4;
            RealmModel realm = currentSession.realms().getRealm("test");

            // Ensure currentSession was removed
            assertThat(currentSession.authenticationSessions().getRootAuthenticationSession(realm, rootAuthSessionID.get()), nullValue());
        });
    }

    @Test
    @ModelTest
    public void testAuthenticationSessionRestart(KeycloakSession session) {
        AtomicReference<String> parentAuthSessionID = new AtomicReference<>();
        AtomicReference<String> tabID = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionRestart1) -> {
            KeycloakSession currentSession = sessionRestart1;
            RealmModel realm = currentSession.realms().getRealm("test");

            ClientModel client1 = realm.getClientByClientId("test-app");
            UserModel user1 = currentSession.users().getUserByUsername("user1", realm);

            AuthenticationSessionModel authSession = currentSession.authenticationSessions().createRootAuthenticationSession(realm)
                    .createAuthenticationSession(client1);

            parentAuthSessionID.set(authSession.getParentSession().getId());
            tabID.set(authSession.getTabId());

            authSession.setAction("foo");
            authSession.getParentSession().setTimestamp(100);

            authSession.setAuthenticatedUser(user1);
            authSession.setAuthNote("foo", "bar");
            authSession.setClientNote("foo2", "bar2");
            authSession.setExecutionStatus("123", CommonClientSessionModel.ExecutionStatus.SUCCESS);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionRestart2) -> {
            KeycloakSession currentSession = sessionRestart2;
            RealmModel realm = currentSession.realms().getRealm("test");

            // Test restart root authentication session
            ClientModel client1 = realm.getClientByClientId("test-app");
            AuthenticationSessionModel authSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, parentAuthSessionID.get())
                    .getAuthenticationSession(client1, tabID.get());
            authSession.getParentSession().restartSession(realm);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionRestart3) -> {
            KeycloakSession currentSession = sessionRestart3;
            RealmModel realm = currentSession.realms().getRealm("test");

            ClientModel client1 = realm.getClientByClientId("test-app");

            RootAuthenticationSessionModel rootAuthSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, parentAuthSessionID.get());

            assertThat(rootAuthSession.getAuthenticationSession(client1, tabID.get()), nullValue());
            assertThat(rootAuthSession.getTimestamp() > 0, is(true));
        });
    }

    @Test
    @ModelTest
    public void testExpiredAuthSessions(KeycloakSession session) {
        AtomicReference<String> authSessionID = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionExpired) -> {
            KeycloakSession mainSession = sessionExpired;
            try {
                // AccessCodeLifespan = 10 ; AccessCodeLifespanUserAction = 10 ; AccessCodeLifespanLogin = 30
                setAccessCodeLifespan(mainSession, 10, 10, 30);

                createAuthSession(mainSession, authSessionID);
                testExpiredOffset(mainSession, 25, false, authSessionID.get());
                testExpiredOffset(mainSession, 35, true, authSessionID.get());

                // AccessCodeLifespan = Not set ; AccessCodeLifespanUserAction = 10 ; AccessCodeLifespanLogin = Not set
                setAccessCodeLifespan(mainSession, -1, 40, -1);

                createAuthSession(mainSession, authSessionID);
                testExpiredOffset(mainSession, 35, false, authSessionID.get());
                testExpiredOffset(mainSession, 45, true, authSessionID.get());

                // AccessCodeLifespan = 50 ; AccessCodeLifespanUserAction = Not set ; AccessCodeLifespanLogin = Not set
                setAccessCodeLifespan(mainSession, 50, -1, -1);

                createAuthSession(mainSession, authSessionID);
                testExpiredOffset(mainSession, 45, false, authSessionID.get());
                testExpiredOffset(mainSession, 55, true, authSessionID.get());

            } finally {
                Time.setOffset(0);
                session.getKeycloakSessionFactory().publish(new ResetTimeOffsetEvent());
                setAccessCodeLifespan(mainSession, 60, 300, 1800);
            }
        });
    }

    @Test
    @ModelTest
    public void testOnRealmRemoved(KeycloakSession session) {
        AtomicReference<String> authSessionID = new AtomicReference<>();
        AtomicReference<String> authSessionID2 = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sesRealmRemoved1) -> {
            KeycloakSession currentSession = sesRealmRemoved1;
            RealmModel realm = currentSession.realms().getRealm("test");
            RealmModel fooRealm = currentSession.realms().createRealm("foo-realm");

            fooRealm.addClient("foo-client");

            authSessionID.set(currentSession.authenticationSessions().createRootAuthenticationSession(realm).getId());
            authSessionID2.set(currentSession.authenticationSessions().createRootAuthenticationSession(fooRealm).getId());
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sesRealmRemoved2) -> {
            KeycloakSession currentSession = sesRealmRemoved2;

            new RealmManager(currentSession).removeRealm(currentSession.realms().getRealmByName("foo-realm"));
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sesRealmRemoved3) -> {
            KeycloakSession currentSession = sesRealmRemoved3;
            RealmModel realm = currentSession.realms().getRealm("test");

            RootAuthenticationSessionModel authSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID.get());

            assertThat(authSession, notNullValue());
            assertThat(currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID2.get()), nullValue());
        });
    }

    @Test
    @ModelTest
    public void testOnClientRemoved(KeycloakSession session) {
        AtomicReference<String> tab1ID = new AtomicReference<>();
        AtomicReference<String> tab2ID = new AtomicReference<>();
        AtomicReference<String> authSessionID = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sesRealmRemoved1) -> {
            KeycloakSession currentSession = sesRealmRemoved1;
            RealmModel realm = currentSession.realms().getRealm("test");

            authSessionID.set(currentSession.authenticationSessions().createRootAuthenticationSession(realm).getId());

            AuthenticationSessionModel authSession1 = currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID.get()).createAuthenticationSession(realm.getClientByClientId("test-app"));
            AuthenticationSessionModel authSession2 = currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID.get()).createAuthenticationSession(realm.getClientByClientId("third-party"));
            tab1ID.set(authSession1.getTabId());
            tab2ID.set(authSession2.getTabId());

            authSession1.setAuthNote("foo", "bar");
            authSession2.setAuthNote("foo", "baz");
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sesRealmRemoved1) -> {
            KeycloakSession currentSession = sesRealmRemoved1;
            RealmModel realm = currentSession.realms().getRealm("test");

            RootAuthenticationSessionModel rootAuthSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID.get());

            assertThat(rootAuthSession.getAuthenticationSessions().size(), is(2));
            assertThat(rootAuthSession.getAuthenticationSession(realm.getClientByClientId("test-app"), tab1ID.get()).getAuthNote("foo"), is("bar"));
            assertThat(rootAuthSession.getAuthenticationSession(realm.getClientByClientId("third-party"), tab2ID.get()).getAuthNote("foo"), is("baz"));

            new ClientManager(new RealmManager(currentSession)).removeClient(realm, realm.getClientByClientId("third-party"));
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sesRealmRemoved1) -> {
            KeycloakSession currentSession = sesRealmRemoved1;
            RealmModel realm = currentSession.realms().getRealm("test");
            RootAuthenticationSessionModel rootAuthSession = currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID.get());

            assertThat(rootAuthSession.getAuthenticationSession(realm.getClientByClientId("test-app"), tab1ID.get()).getAuthNote("foo"), is("bar"));
            assertThat(rootAuthSession.getAuthenticationSession(realm.getClientByClientId("third-party"), tab2ID.get()), nullValue());

            // Revert client
            realm.addClient("third-party");
        });
    }

    private void testAuthenticationSession(AuthenticationSessionModel authSession, String expectedClientId, String expectedUserId, String expectedAction) {
        assertThat(authSession.getClient().getId(), is(expectedClientId));

        if (expectedUserId == null) {
            assertThat(authSession.getAuthenticatedUser(), nullValue());
        } else {
            assertThat(authSession.getAuthenticatedUser().getId(), is(expectedUserId));
        }

        if (expectedAction == null) {
            assertThat(authSession.getAction(), nullValue());
        } else {
            assertThat(authSession.getAction(), is(expectedAction));
        }
    }

    private void createAuthSession(KeycloakSession session, AtomicReference<String> authSessionID) {

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession createAuthSession) -> {
            KeycloakSession currentSession = createAuthSession;
            RealmModel realm = currentSession.realms().getRealm("test");

            Time.setOffset(0);
            authSessionID.set(currentSession.authenticationSessions().createRootAuthenticationSession(realm).getId());
        });
    }

    private void testExpiredOffset(KeycloakSession session, int offset, boolean isSessionNull, String authSessionID) {

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionExp) -> {
            KeycloakSession currentSession = sessionExp;
            RealmModel realm = currentSession.realms().getRealm("test");

            Time.setOffset(offset);
            currentSession.authenticationSessions().removeExpired(realm);
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionExpVerify) -> {
            KeycloakSession currentSession = sessionExpVerify;
            RealmModel realm = currentSession.realms().getRealm("test");

            if (isSessionNull)
                assertThat(currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID), nullValue());
            else
                assertThat(currentSession.authenticationSessions().getRootAuthenticationSession(realm, authSessionID), notNullValue());
        });
    }

    // If parameter is -1, then the parameter won't change.
    private void setAccessCodeLifespan(KeycloakSession session, int lifespan, int lifespanUserAction, int lifespanLogin) {

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionLifespan) -> {
            KeycloakSession currentSession = sessionLifespan;
            RealmModel realm = currentSession.realms().getRealm("test");

            if (lifespan != -1)
                realm.setAccessCodeLifespan(lifespan);

            if (lifespanUserAction != -1)
                realm.setAccessCodeLifespanUserAction(lifespanUserAction);

            if (lifespanLogin != -1)
                realm.setAccessCodeLifespanLogin(lifespanLogin);
        });
    }

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }
}
