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
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.ModelTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ConcurrentTransactionsTest extends AbstractTestRealmKeycloakTest {

    private static final int LATCH_TIMEOUT_MS = 30000;

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, UserSessionProviderOfflineTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.model");
    }

    private static final Logger logger = Logger.getLogger(ConcurrentTransactionsTest.class);

    @Test
    @ModelTest
    public void persistClient(KeycloakSession session) {

        final ClientModel[] client = {null};
        AtomicReference<String> clientDBIdAtomic = new AtomicReference<>();
        AtomicReference<Exception> exceptionHolder = new AtomicReference<>();

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionSetup) -> {

            RealmModel realm = sessionSetup.realms().getRealm("test");
            sessionSetup.users().addUser(realm, "user1").setEmail("user1@localhost");
            sessionSetup.users().addUser(realm, "user2").setEmail("user2@localhost");

            realm = sessionSetup.realms().createRealm("original");

            client[0] = sessionSetup.realms().addClient(realm, "client");
            client[0].setSecret("old");
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> {
            String clientDBId = client[0].getId();
            clientDBIdAtomic.set(clientDBId);

            final KeycloakSessionFactory sessionFactory = session1.getKeycloakSessionFactory();

            final CountDownLatch transactionsCounter = new CountDownLatch(2);
            final CountDownLatch readLatch = new CountDownLatch(1);
            final CountDownLatch updateLatch = new CountDownLatch(1);

            Thread thread1 = new Thread(() -> {
                KeycloakModelUtils.runJobInTransaction(sessionFactory, session11 -> {
                    try {
                        KeycloakSession currentSession = session11;
                        // Wait until transaction in both threads started
                        transactionsCounter.countDown();
                        logger.info("transaction1 started");
                        if (!transactionsCounter.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Timeout when waiting for transactionsCounter latch in thread1");
                        }

                        // Read client
                        RealmModel realm1 = currentSession.realms().getRealmByName("original");
                        ClientModel client1 = currentSession.realms().getClientByClientId("client", realm1);
                        logger.info("transaction1: Read client finished");
                        readLatch.countDown();

                        // Wait until thread2 updates client and commits
                        if (!updateLatch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Timeout when waiting for updateLatch");
                        }

                        logger.info("transaction1: Going to read client again");

                        client1 = currentSession.realms().getClientByClientId("client", realm1);
                        logger.info("transaction1: secret: " + client1.getSecret());

                    } catch (Exception e) {
                        exceptionHolder.set(e);
                        throw new RuntimeException(e);
                    }
                });
            });

            Thread thread2 = new Thread(() -> {
                KeycloakModelUtils.runJobInTransaction(sessionFactory, session22 -> {
                    try {
                        KeycloakSession currentSession = session22;
                        // Wait until transaction in both threads started
                        transactionsCounter.countDown();
                        logger.info("transaction2 started");
                        if (!transactionsCounter.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Timeout when waiting for transactionsCounter latch in thread2");
                        }

                        // Wait until reader thread reads the client
                        if (!readLatch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Timeout when waiting for readLatch");
                        }

                        logger.info("transaction2: Going to update client secret");

                        RealmModel realm12 = currentSession.realms().getRealmByName("original");
                        ClientModel client12 = currentSession.realms().getClientByClientId("client", realm12);
                        client12.setSecret("new");
                    } catch (Exception e) {
                        exceptionHolder.set(e);
                        throw new RuntimeException(e);
                    }
                });
                logger.info("transaction2: commited");
                updateLatch.countDown();
            });

            thread1.start();
            thread2.start();

            try {
                thread1.join();
                thread2.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (exceptionHolder.get() != null) {
                Assert.fail("Some thread thrown an exception. See the log for the details");
            }

            logger.info("after thread join");
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session2) -> {
            RealmModel realm = session2.realms().getRealmByName("original");
            String clientDBId = clientDBIdAtomic.get();

            ClientModel clientFromCache = session2.realms().getClientById(clientDBId, realm);
            ClientModel clientFromDB = session2.getProvider(RealmProvider.class).getClientById(clientDBId, realm);

            logger.info("SECRET FROM DB : " + clientFromDB.getSecret());
            logger.info("SECRET FROM CACHE : " + clientFromCache.getSecret());

            Assert.assertEquals("new", clientFromDB.getSecret());
            Assert.assertEquals("new", clientFromCache.getSecret());

            session2.sessions().removeUserSessions(realm);

            tearDownRealm(session2, "user1", "user2");
        });
    }


    // KEYCLOAK-3296 , KEYCLOAK-3494
    @Test
    @ModelTest
    public void removeUserAttribute(KeycloakSession session) throws Exception {

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionSet) -> {

            RealmModel realm = sessionSet.realms().createRealm("original");

            UserModel john = sessionSet.users().addUser(realm, "john");
            john.setSingleAttribute("foo", "val1");

            UserModel john2 = sessionSet.users().addUser(realm, "john2");
            john2.setAttribute("foo", Arrays.asList("val1", "val2"));
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session2) -> {

            final KeycloakSessionFactory sessionFactory = session2.getKeycloakSessionFactory();

            AtomicReference<Exception> reference = new AtomicReference<>();

            final CountDownLatch readAttrLatch = new CountDownLatch(2);

            Runnable runnable = () -> {
                try {
                    KeycloakModelUtils.runJobInTransaction(sessionFactory, session1 -> {
                        try {
                            // Read user attribute
                            RealmModel realm = session1.realms().getRealmByName("original");
                            UserModel john = session1.users().getUserByUsername("john", realm);
                            String attrVal = john.getFirstAttribute("foo");

                            UserModel john2 = session1.users().getUserByUsername("john2", realm);
                            String attrVal2 = john2.getFirstAttribute("foo");

                            // Wait until it's read in both threads
                            readAttrLatch.countDown();
                            readAttrLatch.await();

                            // KEYCLOAK-3296 : Remove user attribute in both threads
                            john.removeAttribute("foo");

                            // KEYCLOAK-3494 : Set single attribute in both threads
                            john2.setSingleAttribute("foo", "bar");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (Exception e) {
                    reference.set(e);
                    throw new RuntimeException(e);
                } finally {
                    readAttrLatch.countDown();
                }
            };

            Thread thread1 = new Thread(runnable);
            Thread thread2 = new Thread(runnable);

            thread1.start();
            thread2.start();

            try {
                thread1.join();
                thread2.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            logger.info("removeUserAttribute: after thread join");
            if (reference.get() != null) {
                Assert.fail("Exception happened in some of threads. Details: " + reference.get().getMessage());
            }
        });

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession sessionTearDown) -> {
            tearDownRealm(sessionTearDown, "john", "john2");
        });
    }

    private void tearDownRealm(KeycloakSession session, String user1, String user2) {
        KeycloakSession currentSession = session;

        RealmModel realm = currentSession.realms().getRealmByName("original");

        UserModel realmUser1 = currentSession.users().getUserByUsername(user1, realm);
        UserModel realmUser2 = currentSession.users().getUserByUsername(user2, realm);

        UserManager um = new UserManager(currentSession);
        if (realmUser1 != null) {
            um.removeUser(realm, realmUser1);
        }
        if (realmUser2 != null) {
            um.removeUser(realm, realmUser2);
        }

        Assert.assertTrue(currentSession.realms().removeRealm(realm.getId()));
        Assert.assertThat(currentSession.realms().getRealm(realm.getId()), is(nullValue()));
    }

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }
}