/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
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

import java.util.List;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.ModelTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;
import org.keycloak.testsuite.runonserver.RunOnServerException;

import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class SimpleModelTest extends AbstractKeycloakTest {


    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, SimpleModelTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.model");
    }


    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
    }


    @Test
    @ModelTest
    public void simpleModelTest(KeycloakSession session) {
        log.infof("simpleModelTest");
        RealmModel realm = session.realms().getRealmByName("master");

        Assert.assertNotNull("Master realm was not found!", realm);
    }


    @Test
    @ModelTest
    public void simpleModelTestWithNestedTransactions(KeycloakSession session) {
        log.infof("simpleModelTestWithNestedTransactions");

        // Transaction 1
        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> {

            session1.realms().createRealm("foo");

        });

        // Transaction 2 - should be able to see the created realm. Update it
        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session2) -> {

            RealmModel realm = session2.realms().getRealmByName("foo");
            Assert.assertNotNull(realm);

            realm.setAttribute("bar", "baz");

        });

        // Transaction 3 - Doublecheck update is visible. Then rollback transaction!
        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session3) -> {

            RealmModel realm = session3.realms().getRealmByName("foo");
            Assert.assertNotNull(realm);

            String attrValue = realm.getAttribute("bar");
            Assert.assertEquals("baz", attrValue);

            realm.setAttribute("bar", "baz2");

            session3.getTransactionManager().setRollbackOnly();
        });

        // Transaction 4 - should still see the old value of attribute. Delete realm
        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session4) -> {

            RealmModel realm = session4.realms().getRealmByName("foo");
            Assert.assertNotNull(realm);

            String attrValue = realm.getAttribute("bar");
            Assert.assertEquals("baz", attrValue);

            new RealmManager(session4).removeRealm(realm);
        });
    }


    // Just for the test that AssertionError is correctly propagated
    @Test(expected = AssertionError.class)
    @ModelTest
    public void simpleModelTestWithAssertionError(KeycloakSession session) {
        log.infof("simpleModelTestWithAssertionError");
        RealmModel realm = session.realms().getRealmByName("masterr");

        // This should fail and throw the AssertionError
        Assert.assertNotNull("Master realm was not found!", realm);
    }


    // Just for the test that other exception is correctly propagated
    @Test(expected = RunOnServerException.class)
    @ModelTest
    public void simpleModelTestWithOtherError(KeycloakSession session) {
        log.infof("simpleModelTestWithOtherError");
        throw new RuntimeException("Some strange exception");
    }
}
