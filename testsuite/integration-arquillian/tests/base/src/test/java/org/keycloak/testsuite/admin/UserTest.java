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

package org.keycloak.testsuite.admin;

import org.hamcrest.Matchers;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.common.util.Base64;
import org.keycloak.credential.CredentialModel;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.Constants;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.ErrorRepresentation;
import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.resources.RealmsResource;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.federation.DummyUserFederationProvider;
import org.keycloak.testsuite.federation.DummyUserFederationProviderFactory;
import org.keycloak.testsuite.page.LoginPasswordUpdatePage;
import org.keycloak.testsuite.pages.ErrorPage;
import org.keycloak.testsuite.pages.InfoPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.PageUtils;
import org.keycloak.testsuite.pages.ProceedPage;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;
import org.keycloak.testsuite.util.AdminEventPaths;
import org.keycloak.testsuite.util.ClientBuilder;
import org.keycloak.testsuite.util.GreenMailRule;
import org.keycloak.testsuite.util.MailUtils;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.RealmBuilder;
import org.keycloak.testsuite.util.RoleBuilder;
import org.keycloak.testsuite.util.UserBuilder;
import org.keycloak.util.JsonSerialization;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.keycloak.testsuite.Assert.assertNames;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class UserTest extends AbstractAdminTest {

    @Rule
    public GreenMailRule greenMail = new GreenMailRule();

    @Drone
    protected WebDriver driver;

    @Page
    protected LoginPasswordUpdatePage passwordUpdatePage;

    @ArquillianResource
    protected OAuthClient oAuthClient;

    @Page
    protected InfoPage infoPage;

    @Page
    protected ProceedPage proceedPage;

    @Page
    protected ErrorPage errorPage;

    @Page
    protected LoginPage loginPage;

    @Deployment
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(
                AbstractAdminTest.class,
                AbstractTestRealmKeycloakTest.class,
                DummyUserFederationProviderFactory.class, DummyUserFederationProvider.class,
                UserResource.class);
    }

    public String createUser() {
        return createUser("user1", "user1@localhost");
    }

    public String createUser(String username, String email) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setRequiredActions(Collections.emptyList());
        user.setEnabled(true);

        return createUser(user);
    }

    private String createUser(UserRepresentation userRep) {
        return createUser(userRep, true);
    }

    private String createUser(UserRepresentation userRep, boolean assertAdminEvent) {
        Response response = realm.users().create(userRep);
        String createdId = ApiUtil.getCreatedId(response);
        response.close();

        if (assertAdminEvent) {
            assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.userResourcePath(createdId), userRep,
                    ResourceType.USER);
        }

        getCleanup().addUserId(createdId);

        return createdId;
    }

    private void updateUser(UserResource user, UserRepresentation userRep) {
        user.update(userRep);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, AdminEventPaths.userResourcePath(userRep.getId()), userRep, ResourceType.USER);
    }

    @Test
    public void verifyCreateUser() {
        createUser();
    }

    @Test
    public void createDuplicatedUser1() {
        createUser();

        UserRepresentation user = new UserRepresentation();
        user.setUsername("user1");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        assertAdminEvents.assertEmpty();

        // Just to show how to retrieve underlying error message
        ErrorRepresentation error = response.readEntity(ErrorRepresentation.class);
        Assert.assertEquals("User exists with same username", error.getErrorMessage());

        response.close();
    }

    @Test
    public void createDuplicatedUser2() {
        createUser();

        UserRepresentation user = new UserRepresentation();
        user.setUsername("user2");
        user.setEmail("user1@localhost");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        response.close();
    }

    @Test
    public void createUserWithHashedCredentials() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("user_creds");
        user.setEmail("email@localhost");

        PasswordCredentialModel pcm = PasswordCredentialModel.createFromValues("my-algorithm", "theSalt".getBytes(), 22, "ABC");
        CredentialRepresentation hashedPassword = ModelToRepresentation.toRepresentation(pcm);
        hashedPassword.setCreatedDate(1001L);
        hashedPassword.setUserLabel("deviceX");
        hashedPassword.setType(CredentialRepresentation.PASSWORD);

        user.setCredentials(Arrays.asList(hashedPassword));

        createUser(user);

        CredentialModel credentialHashed = fetchCredentials("user_creds");
        PasswordCredentialModel pcmh = PasswordCredentialModel.createFromCredentialModel(credentialHashed);
        assertNotNull("Expecting credential", credentialHashed);
        assertEquals("my-algorithm", pcmh.getPasswordCredentialData().getAlgorithm());
        assertEquals(Long.valueOf(1001), credentialHashed.getCreatedDate());
        assertEquals("deviceX", credentialHashed.getUserLabel());
        assertEquals(22, pcmh.getPasswordCredentialData().getHashIterations());
        assertEquals("ABC", pcmh.getPasswordSecretData().getValue());
        assertEquals("theSalt", new String(pcmh.getPasswordSecretData().getSalt()));
        assertEquals(CredentialRepresentation.PASSWORD, credentialHashed.getType());
    }


    @Test
    public void createUserWithDeprecatedCredentialsFormat() throws IOException {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("user_creds");
        user.setEmail("email@localhost");

        PasswordCredentialModel pcm = PasswordCredentialModel.createFromValues("my-algorithm", "theSalt".getBytes(), 22, "ABC");
        //CredentialRepresentation hashedPassword = ModelToRepresentation.toRepresentation(pcm);
        String deprecatedCredential = "{\n" +
                "      \"type\" : \"password\",\n" +
                "      \"hashedSaltedValue\" : \"" + pcm.getPasswordSecretData().getValue() + "\",\n" +
                "      \"salt\" : \"" + Base64.encodeBytes(pcm.getPasswordSecretData().getSalt()) + "\",\n" +
                "      \"hashIterations\" : " + pcm.getPasswordCredentialData().getHashIterations() + ",\n" +
                "      \"algorithm\" : \"" + pcm.getPasswordCredentialData().getAlgorithm() + "\"\n" +
                "    }";

        CredentialRepresentation deprecatedHashedPassword = JsonSerialization.readValue(deprecatedCredential, CredentialRepresentation.class);
        Assert.assertNotNull(deprecatedHashedPassword.getHashedSaltedValue());
        Assert.assertNull(deprecatedHashedPassword.getCredentialData());

        deprecatedHashedPassword.setCreatedDate(1001l);
        deprecatedHashedPassword.setUserLabel("deviceX");
        deprecatedHashedPassword.setType(CredentialRepresentation.PASSWORD);

        user.setCredentials(Arrays.asList(deprecatedHashedPassword));

        createUser(user, false);

        CredentialModel credentialHashed = fetchCredentials("user_creds");
        PasswordCredentialModel pcmh = PasswordCredentialModel.createFromCredentialModel(credentialHashed);
        assertNotNull("Expecting credential", credentialHashed);
        assertEquals("my-algorithm", pcmh.getPasswordCredentialData().getAlgorithm());
        assertEquals(Long.valueOf(1001), credentialHashed.getCreatedDate());
        assertEquals("deviceX", credentialHashed.getUserLabel());
        assertEquals(22, pcmh.getPasswordCredentialData().getHashIterations());
        assertEquals("ABC", pcmh.getPasswordSecretData().getValue());
        assertEquals("theSalt", new String(pcmh.getPasswordSecretData().getSalt()));
        assertEquals(CredentialRepresentation.PASSWORD, credentialHashed.getType());
    }

    @Test
    public void updateUserWithHashedCredentials() {
        String userId = createUser("user_hashed_creds", "user_hashed_creds@localhost");

        byte[] salt = new byte[]{-69, 85, 87, 99, 26, -107, 125, 99, -77, 30, -111, 118, 108, 100, -117, -56};

        PasswordCredentialModel credentialModel = PasswordCredentialModel.createFromValues("pbkdf2-sha256", salt,
                27500, "uskEPZWMr83pl2mzNB95SFXfIabe2UH9ClENVx/rrQqOjFEjL2aAOGpWsFNNF3qoll7Qht2mY5KxIDm3Rnve2w==");
        credentialModel.setCreatedDate(1001l);
        CredentialRepresentation hashedPassword = ModelToRepresentation.toRepresentation(credentialModel);

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setCredentials(Collections.singletonList(hashedPassword));

        realm.users().get(userId).update(userRepresentation);

        String accountUrl = RealmsResource.accountUrl(UriBuilder.fromUri(getAuthServerRoot())).build(REALM_NAME).toString();

        driver.navigate().to(accountUrl);

        assertEquals("Log In", PageUtils.getPageTitle(driver));

        loginPage.login("user_hashed_creds", "admin");

        assertTrue(driver.getTitle().contains("Account Management"));
    }

    @Test
    public void createUserWithTempolaryCredentials() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("user_temppw");
        user.setEmail("email.temppw@localhost");

        CredentialRepresentation password = new CredentialRepresentation();
        password.setValue("password");
        password.setType(CredentialRepresentation.PASSWORD);
        password.setTemporary(true);
        user.setCredentials(Arrays.asList(password));

        String userId = createUser(user);

        UserRepresentation userRep = realm.users().get(userId).toRepresentation();
        Assert.assertEquals(1, userRep.getRequiredActions().size());
        Assert.assertEquals(UserModel.RequiredAction.UPDATE_PASSWORD.toString(), userRep.getRequiredActions().get(0));
    }

    @Test
    public void createUserWithRawCredentials() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("user_rawpw");
        user.setEmail("email.raw@localhost");

        CredentialRepresentation rawPassword = new CredentialRepresentation();
        rawPassword.setValue("ABCD");
        rawPassword.setType(CredentialRepresentation.PASSWORD);
        user.setCredentials(Arrays.asList(rawPassword));

        createUser(user);

        CredentialModel credential = fetchCredentials("user_rawpw");
        assertNotNull("Expecting credential", credential);
        PasswordCredentialModel pcm = PasswordCredentialModel.createFromCredentialModel(credential);
        assertEquals(PasswordPolicy.HASH_ALGORITHM_DEFAULT, pcm.getPasswordCredentialData().getAlgorithm());
        assertEquals(PasswordPolicy.HASH_ITERATIONS_DEFAULT, pcm.getPasswordCredentialData().getHashIterations());
        assertNotEquals("ABCD", pcm.getPasswordSecretData().getValue());
        assertEquals(CredentialRepresentation.PASSWORD, credential.getType());
    }

    private CredentialModel fetchCredentials(String username) {
        return getTestingClient().server(REALM_NAME).fetch(session -> {
            RealmModel realm = session.getContext().getRealm();
            UserModel user = session.users().getUserByUsername(username, realm);
            List<CredentialModel> storedCredentialsByType = session.userCredentialManager().getStoredCredentialsByType(realm, user, CredentialRepresentation.PASSWORD);
            System.out.println(storedCredentialsByType.size());
            return storedCredentialsByType.get(0);
        }, CredentialModel.class);
    }

    @Test
    public void createDuplicatedUser3() {
        createUser();

        UserRepresentation user = new UserRepresentation();
        user.setUsername("User1");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        response.close();
    }

    @Test
    public void createDuplicatedUser4() {
        createUser();

        UserRepresentation user = new UserRepresentation();
        user.setUsername("USER1");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        response.close();
    }

    @Test
    public void createDuplicatedUser5() {
        createUser();

        UserRepresentation user = new UserRepresentation();
        user.setUsername("user2");
        user.setEmail("User1@localhost");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        response.close();
    }

    @Test
    public void createDuplicatedUser6() {
        createUser();

        UserRepresentation user = new UserRepresentation();
        user.setUsername("user2");
        user.setEmail("user1@LOCALHOST");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        response.close();
    }

    @Test
    public void createDuplicatedUser7() {
        createUser("user1", "USer1@Localhost");

        UserRepresentation user = new UserRepresentation();
        user.setUsername("user2");
        user.setEmail("user1@localhost");
        Response response = realm.users().create(user);
        assertEquals(409, response.getStatus());
        response.close();

        assertAdminEvents.assertEmpty();

    }

    // KEYCLOAK-7015
    @Test
    public void createTwoUsersWithEmptyStringEmails() {
        createUser("user1", "");
        createUser("user2", "");
    }

    @Test
    public void createUserWithFederationLink() {

        // add a dummy federation provider
        ComponentRepresentation dummyFederationProvider = new ComponentRepresentation();
        dummyFederationProvider.setId(DummyUserFederationProviderFactory.PROVIDER_NAME);
        dummyFederationProvider.setName(DummyUserFederationProviderFactory.PROVIDER_NAME);
        dummyFederationProvider.setProviderId(DummyUserFederationProviderFactory.PROVIDER_NAME);
        dummyFederationProvider.setProviderType(UserStorageProvider.class.getName());
        adminClient.realms().realm(REALM_NAME).components().add(dummyFederationProvider);

        assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.componentPath(DummyUserFederationProviderFactory.PROVIDER_NAME), dummyFederationProvider, ResourceType.COMPONENT);

        UserRepresentation user = new UserRepresentation();
        user.setUsername("user1");
        user.setEmail("user1@localhost");
        user.setFederationLink(DummyUserFederationProviderFactory.PROVIDER_NAME);

        String userId = createUser(user);

        // fetch user again and see federation link filled in
        UserRepresentation createdUser = realm.users().get(userId).toRepresentation();
        assertNotNull(createdUser);
        assertEquals(user.getFederationLink(), createdUser.getFederationLink());
    }

    private List<String> createUsers() {
        List<String> ids = new ArrayList<>();

        for (int i = 1; i < 10; i++) {
            UserRepresentation user = new UserRepresentation();
            user.setUsername("username" + i);
            user.setEmail("user" + i + "@localhost");
            user.setFirstName("First" + i);
            user.setLastName("Last" + i);

            ids.add(createUser(user));
        }

        return ids;
    }

    @Test
    public void searchByEmail() {
        createUsers();

        List<UserRepresentation> users = realm.users().search(null, null, null, "user1@localhost", null, null);
        assertEquals(1, users.size());

        users = realm.users().search(null, null, null, "@localhost", null, null);
        assertEquals(9, users.size());
    }

    @Test
    public void searchByUsername() {
        createUsers();

        List<UserRepresentation> users = realm.users().search("username1", null, null, null, null, null);
        assertEquals(1, users.size());

        users = realm.users().search("user", null, null, null, null, null);
        assertEquals(9, users.size());
    }

    @Test
    public void searchById() {
        String expectedUserId = createUsers().get(0);
        List<UserRepresentation> users = realm.users().search("id:" + expectedUserId, null, null);

        assertEquals(1, users.size());
        assertEquals(expectedUserId, users.get(0).getId());

        users = realm.users().search("id:   " + expectedUserId + "     ", null, null);

        assertEquals(1, users.size());
        assertEquals(expectedUserId, users.get(0).getId());
    }

    @Test
    public void search() {
        createUsers();

        List<UserRepresentation> users = realm.users().search("username1", null, null);
        assertEquals(1, users.size());

        users = realm.users().search("first1", null, null);
        assertEquals(1, users.size());

        users = realm.users().search("last", null, null);
        assertEquals(9, users.size());
    }

    @Test
    public void count() {
        createUsers();

        Integer count = realm.users().count();
        assertEquals(9, count.intValue());
    }

    @Test
    public void countUsersNotServiceAccount() {
        createUsers();

        Integer count = realm.users().count();
        assertEquals(9, count.intValue());

        ClientRepresentation client = new ClientRepresentation();

        client.setClientId("test-client");
        client.setPublicClient(false);
        client.setSecret("secret");
        client.setServiceAccountsEnabled(true);
        client.setEnabled(true);
        client.setRedirectUris(Arrays.asList("http://url"));

        getAdminClient().realm(REALM_NAME).clients().create(client);

        // KEYCLOAK-5660, should not consider service accounts
        assertEquals(9, realm.users().count().intValue());
    }

    @Test
    public void delete() {
        String userId = createUser();
        Response response = realm.users().delete(userId);
        assertEquals(204, response.getStatus());
        response.close();
        assertAdminEvents.assertEvent(realmId, OperationType.DELETE, AdminEventPaths.userResourcePath(userId), ResourceType.USER);
    }

    @Test
    public void deleteNonExistent() {
        Response response = realm.users().delete("does-not-exist");
        assertEquals(404, response.getStatus());
        response.close();
        assertAdminEvents.assertEmpty();
    }

    @Test
    public void searchPaginated() {
        createUsers();

        List<UserRepresentation> users = realm.users().search("username", 0, 1);
        assertEquals(1, users.size());
        assertEquals("username1", users.get(0).getUsername());

        users = realm.users().search("username", 5, 2);
        assertEquals(2, users.size());
        assertEquals("username6", users.get(0).getUsername());
        assertEquals("username7", users.get(1).getUsername());

        users = realm.users().search("username", 7, 20);
        assertEquals(2, users.size());
        assertEquals("username8", users.get(0).getUsername());
        assertEquals("username9", users.get(1).getUsername());

        users = realm.users().search("username", 0, 20);
        assertEquals(9, users.size());
    }

    @Test
    public void getFederatedIdentities() {
        // Add sample identity provider
        addSampleIdentityProvider();

        // Add sample user
        String id = createUser();
        UserResource user = realm.users().get(id);
        assertEquals(0, user.getFederatedIdentity().size());

        // Add social link to the user
        FederatedIdentityRepresentation link = new FederatedIdentityRepresentation();
        link.setUserId("social-user-id");
        link.setUserName("social-username");
        Response response = user.addFederatedIdentity("social-provider-id", link);
        assertEquals(204, response.getStatus());
        assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.userFederatedIdentityLink(id, "social-provider-id"), link, ResourceType.USER);

        // Verify social link is here
        user = realm.users().get(id);
        List<FederatedIdentityRepresentation> federatedIdentities = user.getFederatedIdentity();
        assertEquals(1, federatedIdentities.size());
        link = federatedIdentities.get(0);
        assertEquals("social-provider-id", link.getIdentityProvider());
        assertEquals("social-user-id", link.getUserId());
        assertEquals("social-username", link.getUserName());

        // Remove social link now
        user.removeFederatedIdentity("social-provider-id");
        assertAdminEvents.assertEvent(realmId, OperationType.DELETE, AdminEventPaths.userFederatedIdentityLink(id, "social-provider-id"), ResourceType.USER);
        assertEquals(0, user.getFederatedIdentity().size());

        removeSampleIdentityProvider();
    }

    private void addSampleIdentityProvider() {
        List<IdentityProviderRepresentation> providers = realm.identityProviders().findAll();
        Assert.assertEquals(0, providers.size());

        IdentityProviderRepresentation rep = new IdentityProviderRepresentation();
        rep.setAlias("social-provider-id");
        rep.setProviderId("social-provider-type");

        realm.identityProviders().create(rep);
        assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.identityProviderPath(rep.getAlias()), rep, ResourceType.IDENTITY_PROVIDER);
    }

    private void removeSampleIdentityProvider() {
        IdentityProviderResource resource = realm.identityProviders().get("social-provider-id");
        Assert.assertNotNull(resource);
        resource.remove();
        assertAdminEvents.assertEvent(realmId, OperationType.DELETE, AdminEventPaths.identityProviderPath("social-provider-id"), ResourceType.IDENTITY_PROVIDER);
    }

    @Test
    public void addRequiredAction() {
        String id = createUser();

        UserResource user = realm.users().get(id);
        assertTrue(user.toRepresentation().getRequiredActions().isEmpty());

        UserRepresentation userRep = user.toRepresentation();
        userRep.getRequiredActions().add("UPDATE_PASSWORD");
        updateUser(user, userRep);

        assertEquals(1, user.toRepresentation().getRequiredActions().size());
        assertEquals("UPDATE_PASSWORD", user.toRepresentation().getRequiredActions().get(0));
    }

    @Test
    public void removeRequiredAction() {
        String id = createUser();

        UserResource user = realm.users().get(id);
        assertTrue(user.toRepresentation().getRequiredActions().isEmpty());

        UserRepresentation userRep = user.toRepresentation();
        userRep.getRequiredActions().add("UPDATE_PASSWORD");
        updateUser(user, userRep);

        user = realm.users().get(id);
        userRep = user.toRepresentation();
        userRep.getRequiredActions().clear();
        updateUser(user, userRep);

        assertTrue(user.toRepresentation().getRequiredActions().isEmpty());
    }

    @Test
    public void attributes() {
        UserRepresentation user1 = new UserRepresentation();
        user1.setUsername("user1");
        user1.singleAttribute("attr1", "value1user1");
        user1.singleAttribute("attr2", "value2user1");

        String user1Id = createUser(user1);

        UserRepresentation user2 = new UserRepresentation();
        user2.setUsername("user2");
        user2.singleAttribute("attr1", "value1user2");
        List<String> vals = new ArrayList<>();
        vals.add("value2user2");
        vals.add("value2user2_2");
        user2.getAttributes().put("attr2", vals);

        String user2Id = createUser(user2);

        user1 = realm.users().get(user1Id).toRepresentation();
        assertEquals(2, user1.getAttributes().size());
        assertAttributeValue("value1user1", user1.getAttributes().get("attr1"));
        assertAttributeValue("value2user1", user1.getAttributes().get("attr2"));

        user2 = realm.users().get(user2Id).toRepresentation();
        assertEquals(2, user2.getAttributes().size());
        assertAttributeValue("value1user2", user2.getAttributes().get("attr1"));
        vals = user2.getAttributes().get("attr2");
        assertEquals(2, vals.size());
        assertTrue(vals.contains("value2user2") && vals.contains("value2user2_2"));

        user1.singleAttribute("attr1", "value3user1");
        user1.singleAttribute("attr3", "value4user1");

        updateUser(realm.users().get(user1Id), user1);

        user1 = realm.users().get(user1Id).toRepresentation();
        assertEquals(3, user1.getAttributes().size());
        assertAttributeValue("value3user1", user1.getAttributes().get("attr1"));
        assertAttributeValue("value2user1", user1.getAttributes().get("attr2"));
        assertAttributeValue("value4user1", user1.getAttributes().get("attr3"));

        user1.getAttributes().remove("attr1");
        updateUser(realm.users().get(user1Id), user1);

        user1 = realm.users().get(user1Id).toRepresentation();
        assertEquals(2, user1.getAttributes().size());
        assertAttributeValue("value2user1", user1.getAttributes().get("attr2"));
        assertAttributeValue("value4user1", user1.getAttributes().get("attr3"));

        user1.getAttributes().clear();
        updateUser(realm.users().get(user1Id), user1);

        user1 = realm.users().get(user1Id).toRepresentation();
        assertNull(user1.getAttributes());
    }

    private void assertAttributeValue(String expectedValue, List<String> attrValues) {
        assertEquals(1, attrValues.size());
        assertEquals(expectedValue, attrValues.get(0));
    }

    @Test
    public void sendResetPasswordEmail() {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setUsername("user1");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);
        List<String> actions = new LinkedList<>();
        try {
            user.executeActionsEmail(actions);
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("User email missing", error.getErrorMessage());
        }
        try {
            userRep = user.toRepresentation();
            userRep.setEmail("user1@localhost");
            userRep.setEnabled(false);
            updateUser(user, userRep);

            user.executeActionsEmail(actions);
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("User is disabled", error.getErrorMessage());
        }
        try {
            userRep.setEnabled(true);
            updateUser(user, userRep);

            user.executeActionsEmail("invalidClientId", "invalidUri", actions);
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("Client doesn't exist", error.getErrorMessage());
        }
    }

    @Test
    public void sendResetPasswordEmailSuccess() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);
        List<String> actions = new LinkedList<>();
        actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());
        user.executeActionsEmail(actions);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

        Assert.assertEquals(1, greenMail.getReceivedMessages().length);

        MimeMessage message = greenMail.getReceivedMessages()[0];

        MailUtils.EmailBody body = MailUtils.getBody(message);

        assertTrue(body.getText().contains("Update Password"));
        assertTrue(body.getText().contains("your Admin-client-test account"));
        assertTrue(body.getText().contains("This link will expire within 12 hours"));

        assertTrue(body.getHtml().contains("Update Password"));
        assertTrue(body.getHtml().contains("your Admin-client-test account"));
        assertTrue(body.getHtml().contains("This link will expire within 12 hours"));

        String link = MailUtils.getPasswordResetEmailLink(body);

        driver.navigate().to(link);

        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
        proceedPage.clickProceedLink();
        passwordUpdatePage.assertCurrent();

        passwordUpdatePage.changePassword("new-pass", "new-pass");

        assertEquals("Your account has been updated.", PageUtils.getPageTitle(driver));

        driver.navigate().to(link);

        assertEquals("We are sorry...", PageUtils.getPageTitle(driver));
    }

    @Test
    public void sendResetPasswordEmailSuccessTwoLinks() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);
        List<String> actions = new LinkedList<>();
        actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());
        user.executeActionsEmail(actions);
        user.executeActionsEmail(actions);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

        Assert.assertEquals(2, greenMail.getReceivedMessages().length);

        int i = 1;
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            String link = MailUtils.getPasswordResetEmailLink(message);

            driver.navigate().to(link);

            proceedPage.assertCurrent();
            Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
            proceedPage.clickProceedLink();
            passwordUpdatePage.assertCurrent();

            passwordUpdatePage.changePassword("new-pass" + i, "new-pass" + i);
            i++;

            assertEquals("Your account has been updated.", PageUtils.getPageTitle(driver));
        }

        for (MimeMessage message : greenMail.getReceivedMessages()) {
            String link = MailUtils.getPasswordResetEmailLink(message);
            driver.navigate().to(link);
            errorPage.assertCurrent();
        }
    }

    @Test
    public void sendResetPasswordEmailSuccessTwoLinksReverse() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);
        List<String> actions = new LinkedList<>();
        actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());
        user.executeActionsEmail(actions);
        user.executeActionsEmail(actions);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

        Assert.assertEquals(2, greenMail.getReceivedMessages().length);

        int i = 1;
        for (int j = greenMail.getReceivedMessages().length - 1; j >= 0; j--) {
            MimeMessage message = greenMail.getReceivedMessages()[j];

            String link = MailUtils.getPasswordResetEmailLink(message);

            driver.navigate().to(link);

            proceedPage.assertCurrent();
            Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
            proceedPage.clickProceedLink();
            passwordUpdatePage.assertCurrent();

            passwordUpdatePage.changePassword("new-pass" + i, "new-pass" + i);
            i++;

            assertEquals("Your account has been updated.", PageUtils.getPageTitle(driver));
        }

        for (MimeMessage message : greenMail.getReceivedMessages()) {
            String link = MailUtils.getPasswordResetEmailLink(message);
            driver.navigate().to(link);
            errorPage.assertCurrent();
        }
    }

    @Test
    public void sendResetPasswordEmailSuccessLinkOpenDoesNotExpireWhenOpenedOnly() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);
        List<String> actions = new LinkedList<>();
        actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());
        user.executeActionsEmail(actions);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

        Assert.assertEquals(1, greenMail.getReceivedMessages().length);

        MimeMessage message = greenMail.getReceivedMessages()[0];

        String link = MailUtils.getPasswordResetEmailLink(message);

        driver.navigate().to(link);

        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
        proceedPage.clickProceedLink();
        passwordUpdatePage.assertCurrent();

        driver.manage().deleteAllCookies();
        driver.navigate().to("about:blank");

        driver.navigate().to(link);

        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
        proceedPage.clickProceedLink();
        passwordUpdatePage.assertCurrent();

        passwordUpdatePage.changePassword("new-pass", "new-pass");

        assertEquals("Your account has been updated.", PageUtils.getPageTitle(driver));
    }

    @Test
    public void sendResetPasswordEmailSuccessTokenShortLifespan() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        final AtomicInteger originalValue = new AtomicInteger();

        RealmRepresentation realmRep = realm.toRepresentation();
        originalValue.set(realmRep.getActionTokenGeneratedByAdminLifespan());
        realmRep.setActionTokenGeneratedByAdminLifespan(60);
        realm.update(realmRep);

        try {
            UserResource user = realm.users().get(id);
            List<String> actions = new LinkedList<>();
            actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());
            user.executeActionsEmail(actions);

            Assert.assertEquals(1, greenMail.getReceivedMessages().length);

            MimeMessage message = greenMail.getReceivedMessages()[0];

            String link = MailUtils.getPasswordResetEmailLink(message);

            setTimeOffset(70);

            driver.navigate().to(link);

            errorPage.assertCurrent();
            assertEquals("Action expired.", errorPage.getError());
        } finally {
            setTimeOffset(0);

            realmRep.setActionTokenGeneratedByAdminLifespan(originalValue.get());
            realm.update(realmRep);
        }
    }

    @Test
    public void sendResetPasswordEmailSuccessWithRecycledAuthSession() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);
        List<String> actions = new LinkedList<>();
        actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());

        // The following block creates a client and requests updating password with redirect to this client.
        // After clicking the link (starting a fresh auth session with client), the user goes away and sends the email
        // with password reset again - now without the client - and attempts to complete the password reset.
        {
            ClientRepresentation client = new ClientRepresentation();
            client.setClientId("myclient2");
            client.setRedirectUris(new LinkedList<>());
            client.getRedirectUris().add("http://myclient.com/*");
            client.setName("myclient2");
            client.setEnabled(true);
            Response response = realm.clients().create(client);
            String createdId = ApiUtil.getCreatedId(response);
            assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.clientResourcePath(createdId), client, ResourceType.CLIENT);

            user.executeActionsEmail("myclient2", "http://myclient.com/home.html", actions);
            assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

            Assert.assertEquals(1, greenMail.getReceivedMessages().length);

            MimeMessage message = greenMail.getReceivedMessages()[0];

            String link = MailUtils.getPasswordResetEmailLink(message);

            driver.navigate().to(link);
        }

        user.executeActionsEmail(actions);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

        Assert.assertEquals(2, greenMail.getReceivedMessages().length);

        MimeMessage message = greenMail.getReceivedMessages()[greenMail.getReceivedMessages().length - 1];

        String link = MailUtils.getPasswordResetEmailLink(message);

        driver.navigate().to(link);

        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
        proceedPage.clickProceedLink();
        passwordUpdatePage.assertCurrent();

        passwordUpdatePage.changePassword("new-pass", "new-pass");

        assertEquals("Your account has been updated.", PageUtils.getPageTitle(driver));

        driver.navigate().to(link);

        assertEquals("We are sorry...", PageUtils.getPageTitle(driver));
    }

    @Test
    public void sendResetPasswordEmailWithRedirect() throws IOException, MessagingException {

        UserRepresentation userRep = new UserRepresentation();
        userRep.setEnabled(true);
        userRep.setUsername("user1");
        userRep.setEmail("user1@test.com");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);

        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("myclient");
        client.setRedirectUris(new LinkedList<>());
        client.getRedirectUris().add("http://myclient.com/*");
        client.setName("myclient");
        client.setEnabled(true);
        Response response = realm.clients().create(client);
        String createdId = ApiUtil.getCreatedId(response);
        assertAdminEvents.assertEvent(realmId, OperationType.CREATE, AdminEventPaths.clientResourcePath(createdId), client, ResourceType.CLIENT);


        List<String> actions = new LinkedList<>();
        actions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());

        try {
            // test that an invalid redirect uri is rejected.
            user.executeActionsEmail("myclient", "http://unregistered-uri.com/", actions);
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("Invalid redirect uri.", error.getErrorMessage());
        }


        user.executeActionsEmail("myclient", "http://myclient.com/home.html", actions);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/execute-actions-email", ResourceType.USER);

        Assert.assertEquals(1, greenMail.getReceivedMessages().length);

        MimeMessage message = greenMail.getReceivedMessages()[0];

        String link = MailUtils.getPasswordResetEmailLink(message);

        driver.navigate().to(link);

        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Update Password"));
        proceedPage.clickProceedLink();
        passwordUpdatePage.assertCurrent();

        passwordUpdatePage.changePassword("new-pass", "new-pass");

        assertEquals("Your account has been updated.", driver.findElement(By.id("kc-page-title")).getText());

        String pageSource = driver.getPageSource();

        // check to make sure the back link is set.
        Assert.assertTrue(pageSource.contains("http://myclient.com/home.html"));

        driver.navigate().to(link);

        assertEquals("We are sorry...", PageUtils.getPageTitle(driver));
    }


    @Test
    public void sendVerifyEmail() throws IOException, MessagingException {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setUsername("user1");

        String id = createUser(userRep);

        UserResource user = realm.users().get(id);

        try {
            user.sendVerifyEmail();
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("User email missing", error.getErrorMessage());
        }
        try {
            userRep = user.toRepresentation();
            userRep.setEmail("user1@localhost");
            userRep.setEnabled(false);
            updateUser(user, userRep);

            user.sendVerifyEmail();
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("User is disabled", error.getErrorMessage());
        }
        try {
            userRep.setEnabled(true);
            updateUser(user, userRep);

            user.sendVerifyEmail("invalidClientId");
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());

            ErrorRepresentation error = e.getResponse().readEntity(ErrorRepresentation.class);
            Assert.assertEquals("Client doesn't exist", error.getErrorMessage());
        }

        user.sendVerifyEmail();
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResourcePath(id) + "/send-verify-email", ResourceType.USER);

        Assert.assertEquals(1, greenMail.getReceivedMessages().length);

        String link = MailUtils.getPasswordResetEmailLink(greenMail.getReceivedMessages()[0]);

        driver.navigate().to(link);

        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Verify Email"));
        proceedPage.clickProceedLink();
        Assert.assertEquals("Your account has been updated.", infoPage.getInfo());

        driver.navigate().to("about:blank");

        driver.navigate().to(link); // It should be possible to use the same action token multiple times
        proceedPage.assertCurrent();
        Assert.assertThat(proceedPage.getInfo(), Matchers.containsString("Verify Email"));
        proceedPage.clickProceedLink();
        Assert.assertEquals("Your account has been updated.", infoPage.getInfo());
    }

    @Test
    public void updateUserWithNewUsername() {
        switchEditUsernameAllowedOn(true);
        String id = createUser();

        UserResource user = realm.users().get(id);
        UserRepresentation userRep = user.toRepresentation();
        userRep.setUsername("user11");
        updateUser(user, userRep);

        userRep = realm.users().get(id).toRepresentation();
        assertEquals("user11", userRep.getUsername());

        // Revert
        switchEditUsernameAllowedOn(false);
    }

    @Test
    public void updateUserWithoutUsername() {
        switchEditUsernameAllowedOn(true);

        String id = createUser();

        UserResource user = realm.users().get(id);

        UserRepresentation rep = new UserRepresentation();
        rep.setFirstName("Firstname");

        user.update(rep);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, AdminEventPaths.userResourcePath(id), rep, ResourceType.USER);

        rep = new UserRepresentation();
        rep.setLastName("Lastname");

        user.update(rep);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, AdminEventPaths.userResourcePath(id), rep, ResourceType.USER);

        rep = realm.users().get(id).toRepresentation();

        assertEquals("user1", rep.getUsername());
        assertEquals("user1@localhost", rep.getEmail());
        assertEquals("Firstname", rep.getFirstName());
        assertEquals("Lastname", rep.getLastName());

        // Revert
        switchEditUsernameAllowedOn(false);
    }

    @Test
    public void updateUserWithNewUsernameNotPossible() {
        String id = createUser();

        UserResource user = realm.users().get(id);
        UserRepresentation userRep = user.toRepresentation();
        userRep.setUsername("user11");
        updateUser(user, userRep);

        userRep = realm.users().get(id).toRepresentation();
        assertEquals("user1", userRep.getUsername());
    }

    @Test
    public void updateUserWithNewUsernameAccessingViaOldUsername() {
        switchEditUsernameAllowedOn(true);
        createUser();

        try {
            UserResource user = realm.users().get("user1");
            UserRepresentation userRep = user.toRepresentation();
            userRep.setUsername("user1");
            updateUser(user, userRep);

            realm.users().get("user11").toRepresentation();
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(404, e.getResponse().getStatus());
        } finally {
            switchEditUsernameAllowedOn(false);
        }
    }

    @Test
    public void updateUserWithExistingUsername() {
        switchEditUsernameAllowedOn(true);
        enableBruteForce(true);
        createUser();

        UserRepresentation userRep = new UserRepresentation();
        userRep.setUsername("user2");

        String createdId = createUser(userRep);

        try {
            UserResource user = realm.users().get(createdId);
            userRep = user.toRepresentation();
            userRep.setUsername("user1");
            user.update(userRep);
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(409, e.getResponse().getStatus());

            // TODO adminEvents: Event queue should be empty, but it's not because of bug in UsersResource.updateUser, which sends event earlier than transaction commit.
            // assertAdminEvents.assertEmpty();
            assertAdminEvents.poll();
        } finally {
            enableBruteForce(false);
            switchEditUsernameAllowedOn(false);
        }
    }

    @Test
    public void updateUserWithRawCredentials() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("user_rawpw");
        user.setEmail("email.raw@localhost");

        CredentialRepresentation rawPassword = new CredentialRepresentation();
        rawPassword.setValue("ABCD");
        rawPassword.setType(CredentialRepresentation.PASSWORD);
        user.setCredentials(Arrays.asList(rawPassword));

        String id = createUser(user);

        PasswordCredentialModel credential = PasswordCredentialModel
                .createFromCredentialModel(fetchCredentials("user_rawpw"));
        assertNotNull("Expecting credential", credential);
        assertEquals(PasswordPolicy.HASH_ALGORITHM_DEFAULT, credential.getPasswordCredentialData().getAlgorithm());
        assertEquals(PasswordPolicy.HASH_ITERATIONS_DEFAULT, credential.getPasswordCredentialData().getHashIterations());
        assertNotEquals("ABCD", credential.getPasswordSecretData().getValue());
        assertEquals(CredentialRepresentation.PASSWORD, credential.getType());

        UserResource userResource = realm.users().get(id);
        UserRepresentation userRep = userResource.toRepresentation();

        CredentialRepresentation rawPasswordForUpdate = new CredentialRepresentation();
        rawPasswordForUpdate.setValue("EFGH");
        rawPasswordForUpdate.setType(CredentialRepresentation.PASSWORD);
        userRep.setCredentials(Arrays.asList(rawPasswordForUpdate));

        updateUser(userResource, userRep);

        PasswordCredentialModel updatedCredential = PasswordCredentialModel
                .createFromCredentialModel(fetchCredentials("user_rawpw"));
        assertNotNull("Expecting credential", updatedCredential);
        assertEquals(PasswordPolicy.HASH_ALGORITHM_DEFAULT, updatedCredential.getPasswordCredentialData().getAlgorithm());
        assertEquals(PasswordPolicy.HASH_ITERATIONS_DEFAULT, updatedCredential.getPasswordCredentialData().getHashIterations());
        assertNotEquals("EFGH", updatedCredential.getPasswordSecretData().getValue());
        assertEquals(CredentialRepresentation.PASSWORD, updatedCredential.getType());
    }

    @Test
    public void resetUserPassword() {
        String userId = createUser("user1", "user1@localhost");

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue("password");
        cred.setTemporary(false);

        realm.users().get(userId).resetPassword(cred);
        assertAdminEvents.assertEvent(realmId, OperationType.ACTION, AdminEventPaths.userResetPasswordPath(userId), ResourceType.USER);

        String accountUrl = RealmsResource.accountUrl(UriBuilder.fromUri(getAuthServerRoot())).build(REALM_NAME).toString();

        driver.navigate().to(accountUrl);

        assertEquals("Log In", PageUtils.getPageTitle(driver));

        loginPage.login("user1", "password");

        assertTrue(driver.getTitle().contains("Account Management"));
    }

    @Test
    public void resetUserInvalidPassword() {
        String userId = createUser("user1", "user1@localhost");

        try {
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(" ");
            cred.setTemporary(false);
            realm.users().get(userId).resetPassword(cred);
            fail("Expected failure");
        } catch (ClientErrorException e) {
            assertEquals(400, e.getResponse().getStatus());
            e.getResponse().close();
            assertAdminEvents.assertEmpty();
        }
    }

    @Test
    public void testDefaultRequiredActionAdded() {
        // Add UPDATE_PASSWORD as default required action
        RequiredActionProviderRepresentation updatePasswordReqAction = realm.flows().getRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD.toString());
        updatePasswordReqAction.setDefaultAction(true);
        realm.flows().updateRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD.toString(), updatePasswordReqAction);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, AdminEventPaths.authRequiredActionPath(UserModel.RequiredAction.UPDATE_PASSWORD.toString()), updatePasswordReqAction, ResourceType.REQUIRED_ACTION);

        // Create user
        String userId = createUser("user1", "user1@localhost");

        UserRepresentation userRep = realm.users().get(userId).toRepresentation();
        Assert.assertEquals(1, userRep.getRequiredActions().size());
        Assert.assertEquals(UserModel.RequiredAction.UPDATE_PASSWORD.toString(), userRep.getRequiredActions().get(0));

        // Remove UPDATE_PASSWORD default action
        updatePasswordReqAction = realm.flows().getRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD.toString());
        updatePasswordReqAction.setDefaultAction(false);
        realm.flows().updateRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD.toString(), updatePasswordReqAction);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, AdminEventPaths.authRequiredActionPath(UserModel.RequiredAction.UPDATE_PASSWORD.toString()), updatePasswordReqAction, ResourceType.REQUIRED_ACTION);
    }

    @Test
    public void roleMappings() {
        RealmResource realm = adminClient.realms().realm("test");

        // Enable events
        RealmRepresentation realmRep = RealmBuilder.edit(realm.toRepresentation()).testEventListener().build();
        realm.update(realmRep);

        realm.roles().create(RoleBuilder.create().name("realm-role").build());
        realm.roles().create(RoleBuilder.create().name("realm-composite").build());
        realm.roles().create(RoleBuilder.create().name("realm-child").build());
        realm.roles().get("realm-composite").addComposites(Collections.singletonList(realm.roles().get("realm-child").toRepresentation()));

        Response response = realm.clients().create(ClientBuilder.create().clientId("myclient").build());
        String clientUuid = ApiUtil.getCreatedId(response);
        response.close();

        realm.clients().get(clientUuid).roles().create(RoleBuilder.create().name("client-role").build());
        realm.clients().get(clientUuid).roles().create(RoleBuilder.create().name("client-role2").build());
        realm.clients().get(clientUuid).roles().create(RoleBuilder.create().name("client-composite").build());
        realm.clients().get(clientUuid).roles().create(RoleBuilder.create().name("client-child").build());
        realm.clients().get(clientUuid).roles().get("client-composite").addComposites(Collections.singletonList(realm.clients().get(clientUuid).roles().get("client-child").toRepresentation()));

        response = realm.users().create(UserBuilder.create().username("myuser").build());
        String userId = ApiUtil.getCreatedId(response);
        response.close();

        // Admin events for creating role, client or user tested already in other places
        assertAdminEvents.clear();

        RoleMappingResource roles = realm.users().get(userId).roles();
        assertNames(roles.realmLevel().listAll(), "user", "offline_access", Constants.AUTHZ_UMA_AUTHORIZATION);

        // Add realm roles
        List<RoleRepresentation> l = new LinkedList<>();
        l.add(realm.roles().get("realm-role").toRepresentation());
        l.add(realm.roles().get("realm-composite").toRepresentation());
        roles.realmLevel().add(l);
        assertAdminEvents.assertEvent("test", OperationType.CREATE, AdminEventPaths.userRealmRoleMappingsPath(userId), l, ResourceType.REALM_ROLE_MAPPING);

        // Add client roles
        List<RoleRepresentation> list = Collections.singletonList(realm.clients().get(clientUuid).roles().get("client-role").toRepresentation());
        roles.clientLevel(clientUuid).add(list);
        assertAdminEvents.assertEvent("test", OperationType.CREATE, AdminEventPaths.userClientRoleMappingsPath(userId, clientUuid), list, ResourceType.CLIENT_ROLE_MAPPING);

        list = Collections.singletonList(realm.clients().get(clientUuid).roles().get("client-composite").toRepresentation());
        roles.clientLevel(clientUuid).add(list);
        assertAdminEvents.assertEvent("test", OperationType.CREATE, AdminEventPaths.userClientRoleMappingsPath(userId, clientUuid), ResourceType.CLIENT_ROLE_MAPPING);

        // List realm roles
        assertNames(roles.realmLevel().listAll(), "realm-role", "realm-composite", "user", "offline_access", Constants.AUTHZ_UMA_AUTHORIZATION);
        assertNames(roles.realmLevel().listAvailable(), "admin", "customer-user-premium", "realm-composite-role", "sample-realm-role", "attribute-role");
        assertNames(roles.realmLevel().listEffective(), "realm-role", "realm-composite", "realm-child", "user", "offline_access", Constants.AUTHZ_UMA_AUTHORIZATION);

        // List client roles
        assertNames(roles.clientLevel(clientUuid).listAll(), "client-role", "client-composite");
        assertNames(roles.clientLevel(clientUuid).listAvailable(), "client-role2");
        assertNames(roles.clientLevel(clientUuid).listEffective(), "client-role", "client-composite", "client-child");

        // Get mapping representation
        MappingsRepresentation all = roles.getAll();
        assertNames(all.getRealmMappings(), "realm-role", "realm-composite", "user", "offline_access", Constants.AUTHZ_UMA_AUTHORIZATION);
        assertEquals(2, all.getClientMappings().size());
        assertNames(all.getClientMappings().get("myclient").getMappings(), "client-role", "client-composite");
        assertNames(all.getClientMappings().get("account").getMappings(), "manage-account", "view-profile");

        // Remove realm role
        RoleRepresentation realmRoleRep = realm.roles().get("realm-role").toRepresentation();
        roles.realmLevel().remove(Collections.singletonList(realmRoleRep));
        assertAdminEvents.assertEvent("test", OperationType.DELETE, AdminEventPaths.userRealmRoleMappingsPath(userId), Collections.singletonList(realmRoleRep), ResourceType.REALM_ROLE_MAPPING);

        assertNames(roles.realmLevel().listAll(), "realm-composite", "user", "offline_access", Constants.AUTHZ_UMA_AUTHORIZATION);

        // Remove client role
        RoleRepresentation clientRoleRep = realm.clients().get(clientUuid).roles().get("client-role").toRepresentation();
        roles.clientLevel(clientUuid).remove(Collections.singletonList(clientRoleRep));
        assertAdminEvents.assertEvent("test", OperationType.DELETE, AdminEventPaths.userClientRoleMappingsPath(userId, clientUuid), Collections.singletonList(clientRoleRep), ResourceType.CLIENT_ROLE_MAPPING);

        assertNames(roles.clientLevel(clientUuid).listAll(), "client-composite");
    }

    @Test
    public void defaultMaxResults() {
        UsersResource users = adminClient.realms().realm("test").users();

        for (int i = 0; i < 110; i++) {
            users.create(UserBuilder.create().username("test-" + i).addAttribute("aName", "aValue").build()).close();
        }

        List<UserRepresentation> result = users.search("test", null, null);
        assertEquals(100, result.size());
        for (UserRepresentation user : result) {
            assertThat(user.getAttributes(), Matchers.notNullValue());
            assertThat(user.getAttributes().keySet(), Matchers.hasSize(1));
            assertThat(user.getAttributes(), Matchers.hasEntry(is("aName"), Matchers.contains("aValue")));
        }

        assertEquals(105, users.search("test", 0, 105).size());
        assertEquals(111, users.search("test", 0, 1000).size());
    }

    @Test
    public void defaultMaxResultsBrief() {
        UsersResource users = adminClient.realms().realm("test").users();

        for (int i = 0; i < 110; i++) {
            users.create(UserBuilder.create().username("test-" + i).addAttribute("aName", "aValue").build()).close();
        }

        List<UserRepresentation> result = users.search("test", null, null, true);
        assertEquals(100, result.size());
        for (UserRepresentation user : result) {
            assertThat(user.getAttributes(), Matchers.nullValue());
        }
    }

    @Test
    public void testAccessUserFromOtherRealm() {
        RealmRepresentation firstRealm = new RealmRepresentation();

        firstRealm.setRealm("first-realm");

        adminClient.realms().create(firstRealm);

        realm = adminClient.realm(firstRealm.getRealm());
        realmId = realm.toRepresentation().getId();

        UserRepresentation firstUser = new UserRepresentation();

        firstUser.setUsername("first");
        firstUser.setEmail("first@first-realm.org");

        firstUser.setId(createUser(firstUser, false));

        RealmRepresentation secondRealm = new RealmRepresentation();

        secondRealm.setRealm("second-realm");

        adminClient.realms().create(secondRealm);

        adminClient.realm(firstRealm.getRealm()).users().get(firstUser.getId()).update(firstUser);

        try {
            adminClient.realm(secondRealm.getRealm()).users().get(firstUser.getId()).toRepresentation();
            fail("Should not have access to firstUser from another realm");
        } catch (NotFoundException nfe) {
            // ignore
        }
    }

    private void switchEditUsernameAllowedOn(boolean enable) {
        RealmRepresentation rep = realm.toRepresentation();
        rep.setEditUsernameAllowed(enable);
        realm.update(rep);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, Matchers.nullValue(String.class), rep, ResourceType.REALM);
    }

    private void enableBruteForce(boolean enable) {
        RealmRepresentation rep = realm.toRepresentation();
        rep.setBruteForceProtected(enable);
        realm.update(rep);
        assertAdminEvents.assertEvent(realmId, OperationType.UPDATE, Matchers.nullValue(String.class), rep, ResourceType.REALM);
    }

    @Test
    public void loginShouldFailAfterPasswordDeleted() {
        String userName = "credential-tester";
        String userPass = "s3cr37";
        String userId = createUser(REALM_NAME, userName, userPass);
        getCleanup(REALM_NAME).addUserId(userId);

        String accountUrl = RealmsResource.accountUrl(UriBuilder.fromUri(getAuthServerRoot())).build(REALM_NAME).toString();
        driver.navigate().to(accountUrl);
        assertEquals("Test user should be on the login page.", "Log In", PageUtils.getPageTitle(driver));
        loginPage.login(userName, userPass);
        assertTrue("Test user should be successfully logged in.", driver.getTitle().contains("Account Management"));
        accountPage.logOut();

        Optional<CredentialRepresentation> passwordCredential =
                realm.users().get(userId).credentials().stream()
                        .filter(c -> CredentialRepresentation.PASSWORD.equals(c.getType()))
                        .findFirst();
        assertTrue("Test user should have a password credential set.", passwordCredential.isPresent());
        realm.users().get(userId).removeCredential(passwordCredential.get().getId());

        driver.navigate().to(accountUrl);
        assertEquals("Test user should be on the login page.", "Log In", PageUtils.getPageTitle(driver));
        loginPage.login(userName, userPass);
        assertTrue("Test user should fail to log in after password was deleted.",
                driver.getCurrentUrl().contains(String.format("/realms/%s/login-actions/authenticate", REALM_NAME)));
    }

    @Test
    public void testGetAndMoveCredentials() {
        importTestRealms();

        UserResource user = ApiUtil.findUserByUsernameId(testRealm(), "user-with-two-configured-otp");
        List<CredentialRepresentation> creds = user.credentials();
        List<String> expectedCredIds = Arrays.asList(creds.get(0).getId(), creds.get(1).getId(), creds.get(2).getId());

        // Check actual user credentials
        assertSameIds(expectedCredIds, user.credentials());

        // Move first credential after second one
        user.moveCredentialAfter(expectedCredIds.get(0), expectedCredIds.get(1));
        List<String> newOrderCredIds = Arrays.asList(expectedCredIds.get(1), expectedCredIds.get(0), expectedCredIds.get(2));
        assertSameIds(newOrderCredIds, user.credentials());

        // Move last credential in first position
        user.moveCredentialToFirst(expectedCredIds.get(2));
        newOrderCredIds = Arrays.asList(expectedCredIds.get(2), expectedCredIds.get(1), expectedCredIds.get(0));
        assertSameIds(newOrderCredIds, user.credentials());

        // Restore initial state
        user.moveCredentialToFirst(expectedCredIds.get(1));
        user.moveCredentialToFirst(expectedCredIds.get(0));
        assertSameIds(expectedCredIds, user.credentials());
    }

    private void assertSameIds(List<String> expectedIds, List<CredentialRepresentation> actual) {
        Assert.assertEquals(expectedIds.size(), actual.size());
        for (int i = 0; i < expectedIds.size(); i++) {
            Assert.assertEquals(expectedIds.get(i), actual.get(i).getId());
        }
    }

    @Test
    public void testUpdateCredentials() {
        importTestRealms();

        // Get user user-with-one-configured-otp and assert he has no label linked to its OTP credential
        UserResource user = ApiUtil.findUserByUsernameId(testRealm(), "user-with-one-configured-otp");
        CredentialRepresentation otpCred = user.credentials().get(0);
        Assert.assertNull(otpCred.getUserLabel());

        // Set and check a new label
        String newLabel = "the label";
        user.setCredentialUserLabel(otpCred.getId(), newLabel);
        Assert.assertEquals(newLabel, user.credentials().get(0).getUserLabel());
    }

    @Test
    public void testDeleteCredentials() {
        UserResource user = ApiUtil.findUserByUsernameId(testRealm(), "john-doh@localhost");
        List<CredentialRepresentation> creds = user.credentials();
        Assert.assertEquals(1, creds.size());
        CredentialRepresentation credPasswd = creds.get(0);
        Assert.assertEquals("password", credPasswd.getType());

        // Remove password
        user.removeCredential(credPasswd.getId());
        Assert.assertEquals(0, user.credentials().size());

        // Restore password
        credPasswd.setValue("password");
        user.resetPassword(credPasswd);
        Assert.assertEquals(1, user.credentials().size());
    }
}
