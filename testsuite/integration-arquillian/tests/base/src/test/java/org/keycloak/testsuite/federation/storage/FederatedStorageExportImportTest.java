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
package org.keycloak.testsuite.federation.storage;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.exportimport.ExportImportConfig;
import org.keycloak.exportimport.ExportImportManager;
import org.keycloak.exportimport.dir.DirExportProviderFactory;
import org.keycloak.exportimport.singlefile.SingleFileExportProviderFactory;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.testsuite.AbstractAuthTest;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

import javax.ws.rs.NotFoundException;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class FederatedStorageExportImportTest extends AbstractAuthTest {

    private static final String REALM_NAME = "exported";

    private String exportFileAbsolutePath;
    private String exportDirAbsolutePath;


    @Deployment
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(ComponentExportImportTest.class, AbstractAuthTest.class, RealmResource.class)
                .addPackages(true, "org.keycloak.testsuite");
    }

    @Before
    public void setDirs() {
        File baseDir = new File(System.getProperty("auth.server.config.dir", "target"));

        exportFileAbsolutePath = new File (baseDir, "singleFile-full.json").getAbsolutePath();
        log.infof("Export file: %s", exportFileAbsolutePath);

        exportDirAbsolutePath = baseDir.getAbsolutePath() + File.separator + "dirExport";
        log.infof("Export dir: %s", exportDirAbsolutePath);
    }


    @Override
    public RealmResource testRealmResource() {
        return adminClient.realm(REALM_NAME);
    }


    @After
    public void cleanup() {
        try {
            testRealmResource().remove();
        } catch (NotFoundException ignore) {
        }
    }

    public static PasswordHashProvider getHashProvider(KeycloakSession session, PasswordPolicy policy) {
        PasswordHashProvider hash = session.getProvider(PasswordHashProvider.class, policy.getHashAlgorithm());
        if (hash == null) {
            return session.getProvider(PasswordHashProvider.class, PasswordPolicy.HASH_ALGORITHM_DEFAULT);
        }
        return hash;
    }


    @Test
    public void testSingleFile() {
        ComponentExportImportTest.clearExportImportProperties(testingClient);

        final String userId = "f:1:path";

        testingClient.server().run(session -> {
            RealmModel realm = new RealmManager(session).createRealm(REALM_NAME);
            RoleModel role = realm.addRole("test-role");
            GroupModel group = realm.createGroup("test-group");

            List<String> attrValues = new LinkedList<>();
            attrValues.add("1");
            attrValues.add("2");
            session.userFederatedStorage().setSingleAttribute(realm, userId, "single1", "value1");
            session.userFederatedStorage().setAttribute(realm, userId, "list1", attrValues);
            session.userFederatedStorage().addRequiredAction(realm, userId, "UPDATE_PASSWORD");
            PasswordCredentialModel credential = FederatedStorageExportImportTest.getHashProvider(session, realm.getPasswordPolicy()).encodedCredential("password", realm.
                    getPasswordPolicy().getHashIterations());
            session.userFederatedStorage().createCredential(realm, userId, credential);
            session.userFederatedStorage().grantRole(realm, userId, role);
            session.userFederatedStorage().joinGroup(realm, userId, group);
        });

        final String realmId = testRealmResource().toRepresentation().getId();
        final String groupId = testRealmResource().getGroupByPath("/test-group").getId();
        final String exportFileAbsolutePath = this.exportFileAbsolutePath;

        testingClient.server().run(session -> {
            ExportImportConfig.setProvider(SingleFileExportProviderFactory.PROVIDER_ID);
            ExportImportConfig.setFile(exportFileAbsolutePath);
            ExportImportConfig.setRealmName(REALM_NAME);
            ExportImportConfig.setAction(ExportImportConfig.ACTION_EXPORT);
            new ExportImportManager(session).runExport();
            session.realms().removeRealm(realmId);
        });

        testingClient.server().run(session -> {
            Assert.assertNull(session.realms().getRealmByName(REALM_NAME));
            ExportImportConfig.setAction(ExportImportConfig.ACTION_IMPORT);
            new ExportImportManager(session).runImport();
        });

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(REALM_NAME);
            Assert.assertNotNull(realm);
            RoleModel role = realm.getRole("test-role");
            GroupModel group = realm.getGroupById(groupId);

            Assert.assertEquals(1, session.userFederatedStorage().getStoredUsersCount(realm));
            MultivaluedHashMap<String, String> attributes = session.userFederatedStorage().getAttributes(realm, userId);
            Assert.assertEquals(3, attributes.size());
            Assert.assertEquals("value1", attributes.getFirst("single1"));
            Assert.assertTrue(attributes.getList("list1").contains("1"));
            Assert.assertTrue(attributes.getList("list1").contains("2"));
            Assert.assertTrue(session.userFederatedStorage().getRequiredActions(realm, userId).contains("UPDATE_PASSWORD"));
            Assert.assertTrue(session.userFederatedStorage().getRoleMappings(realm, userId).contains(role));
            Assert.assertTrue(session.userFederatedStorage().getGroups(realm, userId).contains(group));
            List<CredentialModel> creds = session.userFederatedStorage().getStoredCredentials(realm, userId);
            Assert.assertEquals(1, creds.size());
            Assert.assertTrue(FederatedStorageExportImportTest.getHashProvider(session, realm.getPasswordPolicy())
                    .verify("password", PasswordCredentialModel.createFromCredentialModel(creds.get(0))));
        });
    }

    @Test
    public void testDir() {
        ComponentExportImportTest.clearExportImportProperties(testingClient);

        final String userId = "f:1:path";

        testingClient.server().run(session -> {
            RealmModel realm = new RealmManager(session).createRealm(REALM_NAME);

            RoleModel role = realm.addRole("test-role");
            GroupModel group = realm.createGroup("test-group");
            List<String> attrValues = new LinkedList<>();
            attrValues.add("1");
            attrValues.add("2");
            session.userFederatedStorage().setSingleAttribute(realm, userId, "single1", "value1");
            session.userFederatedStorage().setAttribute(realm, userId, "list1", attrValues);
            session.userFederatedStorage().addRequiredAction(realm, userId, "UPDATE_PASSWORD");
            PasswordCredentialModel credential = FederatedStorageExportImportTest.getHashProvider(session, realm.getPasswordPolicy()).encodedCredential("password", realm.
                    getPasswordPolicy().getHashIterations());
            session.userFederatedStorage().createCredential(realm, userId, credential);
            session.userFederatedStorage().grantRole(realm, userId, role);
            session.userFederatedStorage().joinGroup(realm, userId, group);
            session.userFederatedStorage().setNotBeforeForUser(realm, userId, 50);
        });

        final String realmId = testRealmResource().toRepresentation().getId();
        final String groupId = testRealmResource().getGroupByPath("/test-group").getId();
        final String exportDirAbsolutePath = this.exportDirAbsolutePath;

        testingClient.server().run(session -> {
            ExportImportConfig.setProvider(DirExportProviderFactory.PROVIDER_ID);
            ExportImportConfig.setDir(exportDirAbsolutePath);
            ExportImportConfig.setRealmName(REALM_NAME);
            ExportImportConfig.setAction(ExportImportConfig.ACTION_EXPORT);
            new ExportImportManager(session).runExport();
            session.realms().removeRealm(realmId);
        });


        testingClient.server().run(session -> {
            Assert.assertNull(session.realms().getRealmByName(REALM_NAME));
            ExportImportConfig.setAction(ExportImportConfig.ACTION_IMPORT);
            new ExportImportManager(session).runImport();
        });

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName(REALM_NAME);
            Assert.assertNotNull(realm);
            RoleModel role = realm.getRole("test-role");
            GroupModel group = realm.getGroupById(groupId);

            Assert.assertEquals(1, session.userFederatedStorage().getStoredUsersCount(realm));
            MultivaluedHashMap<String, String> attributes = session.userFederatedStorage().getAttributes(realm, userId);
            Assert.assertEquals(3, attributes.size());
            Assert.assertEquals("value1", attributes.getFirst("single1"));
            Assert.assertTrue(attributes.getList("list1").contains("1"));
            Assert.assertTrue(attributes.getList("list1").contains("2"));
            Assert.assertTrue(session.userFederatedStorage().getRequiredActions(realm, userId).contains("UPDATE_PASSWORD"));
            Assert.assertTrue(session.userFederatedStorage().getRoleMappings(realm, userId).contains(role));
            Assert.assertTrue(session.userFederatedStorage().getGroups(realm, userId).contains(group));
            Assert.assertEquals(50, session.userFederatedStorage().getNotBeforeOfUser(realm, userId));
            List<CredentialModel> creds = session.userFederatedStorage().getStoredCredentials(realm, userId);
            Assert.assertEquals(1, creds.size());
            Assert.assertTrue(FederatedStorageExportImportTest.getHashProvider(session, realm.getPasswordPolicy())
                    .verify("password", PasswordCredentialModel.createFromCredentialModel(creds.get(0))));

        });

    }

}
