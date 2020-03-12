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
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.ModelTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;

import java.util.HashSet;
import java.util.Set;

import static org.keycloak.testsuite.admin.AbstractAdminTest.loadJson;
import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;


/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class CompositeRolesModelTest extends AbstractTestRealmKeycloakTest {
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, CompositeRolesModelTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.model");
    }


    public static Set<RoleModel> getRequestedRoles(ClientModel application, UserModel user) {

        Set<RoleModel> requestedRoles = new HashSet<>();

        Set<RoleModel> roleMappings = user.getRoleMappings();
        Set<RoleModel> scopeMappings = application.getScopeMappings();
        Set<RoleModel> appRoles = application.getRoles();
        if (appRoles != null) scopeMappings.addAll(appRoles);

        for (RoleModel role : roleMappings) {
            if (role.getContainer().equals(application)) requestedRoles.add(role);
            for (RoleModel desiredRole : scopeMappings) {
                Set<RoleModel> visited = new HashSet<>();
                applyScope(role, desiredRole, visited, requestedRoles);
            }
        }
        return requestedRoles;
    }



    private static void applyScope(RoleModel role, RoleModel scope, Set<RoleModel> visited, Set<RoleModel> requested) {
        if (visited.contains(scope)) return;
        visited.add(scope);
        if (role.hasRole(scope)) {
            requested.add(scope);
            return;
        }
        if (!scope.isComposite()) return;

        for (RoleModel contained : scope.getComposites()) {
            applyScope(role, contained, visited, requested);
        }
    }

    private static RoleModel getRole(RealmModel realm, String appName, String roleName) {
        if ("realm".equals(appName)) {
            return realm.getRole(roleName);
        } else {
            return realm.getClientByClientId(appName).getRole(roleName);
        }
    }

    private static void assertContains(RealmModel realm, String appName, String roleName, Set<RoleModel> requestedRoles) {
        RoleModel expectedRole = getRole(realm, appName, roleName);

        Assert.assertTrue(requestedRoles.contains(expectedRole));

        // Check if requestedRole has correct role container
        for (RoleModel role : requestedRoles) {
            if (role.equals(expectedRole)) {
                Assert.assertEquals(role.getContainer(), expectedRole.getContainer());
            }
        }
    }

    @Test
    @ModelTest
    public void testNoClientID(KeycloakSession session) {
        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage("Unknown client specification in scope mappings: some-client");

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session1) -> {
            try {
                //RealmManager manager = new RealmManager(session1);
                RealmRepresentation rep = loadJson(getClass().getResourceAsStream("/model/testrealm-noclient-id.json"), RealmRepresentation.class);
                rep.setId("TestNoClientID");
                //manager.importRealm(rep);
                adminClient.realms().create(rep);
            } catch (RuntimeException e) {
            }

        });
    }

    @Test
    @ModelTest
    public void testComposites(KeycloakSession session) {

        KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), (KeycloakSession session5) -> {

            RealmModel realm = session5.realms().getRealm("TestComposites");

            Set<RoleModel> requestedRoles = getRequestedRoles(realm.getClientByClientId("APP_COMPOSITE_APPLICATION"), session.users().getUserByUsername("APP_COMPOSITE_USER", realm));

            Assert.assertEquals(5, requestedRoles.size());
            assertContains(realm, "APP_COMPOSITE_APPLICATION", "APP_COMPOSITE_ROLE", requestedRoles);
            assertContains(realm, "APP_COMPOSITE_APPLICATION", "APP_COMPOSITE_CHILD", requestedRoles);
            assertContains(realm, "APP_COMPOSITE_APPLICATION", "APP_ROLE_2", requestedRoles);
            assertContains(realm, "APP_ROLE_APPLICATION", "APP_ROLE_1", requestedRoles);
            assertContains(realm, "realm", "REALM_ROLE_1", requestedRoles);

            Set<RoleModel> requestedRoles2 = getRequestedRoles(realm.getClientByClientId("APP_COMPOSITE_APPLICATION"), session5.users().getUserByUsername("REALM_APP_COMPOSITE_USER", realm));
            Assert.assertEquals(4, requestedRoles2.size());
            assertContains(realm, "APP_ROLE_APPLICATION", "APP_ROLE_1", requestedRoles2);

            requestedRoles = getRequestedRoles(realm.getClientByClientId("REALM_COMPOSITE_1_APPLICATION"), session5.users().getUserByUsername("REALM_COMPOSITE_1_USER", realm));
            Assert.assertEquals(1, requestedRoles.size());
            assertContains(realm, "realm", "REALM_COMPOSITE_1", requestedRoles);

            requestedRoles = getRequestedRoles(realm.getClientByClientId("REALM_COMPOSITE_2_APPLICATION"), session5.users().getUserByUsername("REALM_COMPOSITE_1_USER", realm));
            Assert.assertEquals(3, requestedRoles.size());
            assertContains(realm, "realm", "REALM_COMPOSITE_1", requestedRoles);
            assertContains(realm, "realm", "REALM_COMPOSITE_CHILD", requestedRoles);
            assertContains(realm, "realm", "REALM_ROLE_4", requestedRoles);

            requestedRoles = getRequestedRoles(realm.getClientByClientId("REALM_ROLE_1_APPLICATION"), session5.users().getUserByUsername("REALM_COMPOSITE_1_USER", realm));
            Assert.assertEquals(1, requestedRoles.size());
            assertContains(realm, "realm", "REALM_ROLE_1", requestedRoles);

            requestedRoles = getRequestedRoles(realm.getClientByClientId("REALM_COMPOSITE_1_APPLICATION"), session5.users().getUserByUsername("REALM_ROLE_1_USER", realm));
            Assert.assertEquals(1, requestedRoles.size());
            assertContains(realm, "realm", "REALM_ROLE_1", requestedRoles);
        });

    }


    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
        log.infof("testcomposites imported");
        RealmRepresentation newRealm = loadJson(getClass().getResourceAsStream("/model/testcomposites2.json"), RealmRepresentation.class);
        newRealm.setId("TestComposites");
        adminClient.realms().create(newRealm);

    }

}
