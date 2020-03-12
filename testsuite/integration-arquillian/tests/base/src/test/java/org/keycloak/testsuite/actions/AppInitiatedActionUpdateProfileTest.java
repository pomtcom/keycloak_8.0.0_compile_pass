/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates
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
package org.keycloak.testsuite.actions;

import org.hamcrest.Matchers;
import org.jboss.arquillian.graphene.page.Page;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.events.Details;
import org.keycloak.events.EventType;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.pages.ErrorPage;
import org.keycloak.testsuite.pages.LoginUpdateProfileEditUsernameAllowedPage;
import org.keycloak.testsuite.util.UserBuilder;

/**
 * @author Stan Silvert
 */
public class AppInitiatedActionUpdateProfileTest extends AbstractAppInitiatedActionTest {

    public AppInitiatedActionUpdateProfileTest() {
        super(UserModel.RequiredAction.UPDATE_PROFILE.name());
    }
    
    @Page
    protected LoginUpdateProfileEditUsernameAllowedPage updateProfilePage;

    @Page
    protected ErrorPage errorPage;
    
    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }

    @Before
    public void beforeTest() {
        ApiUtil.removeUserByUsername(testRealm(), "test-user@localhost");
        UserRepresentation user = UserBuilder.create().enabled(true)
                .username("test-user@localhost")
                .email("test-user@localhost")
                .firstName("Tom")
                .lastName("Brady")
                .build();
        ApiUtil.createUserAndResetPasswordWithAdminClient(testRealm(), user, "password");

        ApiUtil.removeUserByUsername(testRealm(), "john-doh@localhost");
        user = UserBuilder.create().enabled(true)
                .username("john-doh@localhost")
                .email("john-doh@localhost")
                .firstName("John")
                .lastName("Doh")
                .build();
        ApiUtil.createUserAndResetPasswordWithAdminClient(testRealm(), user, "password");
    }
  
    @Test
    public void updateProfile() {
        doAIA();

        loginPage.login("test-user@localhost", "password");
        
        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "new@email.com", "test-user@localhost");

        events.expectRequiredAction(EventType.UPDATE_EMAIL).detail(Details.PREVIOUS_EMAIL, "test-user@localhost").detail(Details.UPDATED_EMAIL, "new@email.com").assertEvent();
        events.expectRequiredAction(EventType.UPDATE_PROFILE).assertEvent();
        events.expectLogin().assertEvent();

        assertKcActionStatus("success");

        // assert user is really updated in persistent store
        UserRepresentation user = ActionUtil.findUserWithAdminClient(adminClient, "test-user@localhost");
        Assert.assertEquals("New first", user.getFirstName());
        Assert.assertEquals("New last", user.getLastName());
        Assert.assertEquals("new@email.com", user.getEmail());
        Assert.assertEquals("test-user@localhost", user.getUsername());
    }
    
    @Test
    // This tests verifies that AIA still works if you call it after you are
    // already logged in.  The other main difference between this and all other
    // AIA tests is that the events are posted in a different order.
    public void updateProfileLoginFirst() {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        
        doAIA();

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "new@email.com", "test-user@localhost");

        events.expectLogin().assertEvent();
        events.expectRequiredAction(EventType.UPDATE_EMAIL).detail(Details.PREVIOUS_EMAIL, "test-user@localhost").detail(Details.UPDATED_EMAIL, "new@email.com").assertEvent();
        events.expectRequiredAction(EventType.UPDATE_PROFILE).assertEvent();

        assertKcActionStatus("success");

        // assert user is really updated in persistent store
        UserRepresentation user = ActionUtil.findUserWithAdminClient(adminClient, "test-user@localhost");
        Assert.assertEquals("New first", user.getFirstName());
        Assert.assertEquals("New last", user.getLastName());
        Assert.assertEquals("new@email.com", user.getEmail());
        Assert.assertEquals("test-user@localhost", user.getUsername());
    }
    
    @Test
    public void cancelUpdateProfile() {
        doAIA();

        loginPage.login("test-user@localhost", "password");
        
        updateProfilePage.assertCurrent();
        updateProfilePage.cancel();

        assertKcActionStatus("cancelled");

        
        // assert nothing was updated in persistent store
        UserRepresentation user = ActionUtil.findUserWithAdminClient(adminClient, "test-user@localhost");
        Assert.assertEquals("Tom", user.getFirstName());
        Assert.assertEquals("Brady", user.getLastName());
        Assert.assertEquals("test-user@localhost", user.getEmail());
        Assert.assertEquals("test-user@localhost", user.getUsername());
    }
    

    @Test
    public void updateUsername() {
        doAIA();
        
        loginPage.login("john-doh@localhost", "password");

        String userId = ActionUtil.findUserWithAdminClient(adminClient, "john-doh@localhost").getId();

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "john-doh@localhost", "new");

        events.expectLogin()
                .event(EventType.UPDATE_PROFILE)
                .detail(Details.USERNAME, "john-doh@localhost")
                .user(userId)
                .session(Matchers.nullValue(String.class))
                .removeDetail(Details.CONSENT)
                .assertEvent();

        assertKcActionStatus("success");

        events.expectLogin().detail(Details.USERNAME, "john-doh@localhost").user(userId).assertEvent();

        // assert user is really updated in persistent store
        UserRepresentation user = ActionUtil.findUserWithAdminClient(adminClient, "new");
        Assert.assertEquals("New first", user.getFirstName());
        Assert.assertEquals("New last", user.getLastName());
        Assert.assertEquals("john-doh@localhost", user.getEmail());
        Assert.assertEquals("new", user.getUsername());
        getCleanup().addUserId(user.getId());
    }

    @Test
    public void updateProfileMissingFirstName() {
        doAIA();

        loginPage.login("test-user@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("", "New last", "new@email.com", "new");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("", updateProfilePage.getFirstName());
        Assert.assertEquals("New last", updateProfilePage.getLastName());
        Assert.assertEquals("new@email.com", updateProfilePage.getEmail());

        Assert.assertEquals("Please specify first name.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileMissingLastName() {
        doAIA();

        loginPage.login("test-user@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "", "new@email.com", "new");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("New first", updateProfilePage.getFirstName());
        Assert.assertEquals("", updateProfilePage.getLastName());
        Assert.assertEquals("new@email.com", updateProfilePage.getEmail());

        Assert.assertEquals("Please specify last name.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileMissingEmail() {
        doAIA();

        loginPage.login("test-user@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "", "new");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("New first", updateProfilePage.getFirstName());
        Assert.assertEquals("New last", updateProfilePage.getLastName());
        Assert.assertEquals("", updateProfilePage.getEmail());

        Assert.assertEquals("Please specify email.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileInvalidEmail() {
        doAIA();

        loginPage.login("test-user@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "invalidemail", "invalid");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("New first", updateProfilePage.getFirstName());
        Assert.assertEquals("New last", updateProfilePage.getLastName());
        Assert.assertEquals("invalidemail", updateProfilePage.getEmail());

        Assert.assertEquals("Invalid email address.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileMissingUsername() {
        doAIA();

        loginPage.login("john-doh@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "new@email.com", "");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("New first", updateProfilePage.getFirstName());
        Assert.assertEquals("New last", updateProfilePage.getLastName());
        Assert.assertEquals("new@email.com", updateProfilePage.getEmail());
        Assert.assertEquals("", updateProfilePage.getUsername());

        Assert.assertEquals("Please specify username.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileDuplicateUsername() {
        doAIA();

        loginPage.login("john-doh@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "new@email.com", "test-user@localhost");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("New first", updateProfilePage.getFirstName());
        Assert.assertEquals("New last", updateProfilePage.getLastName());
        Assert.assertEquals("new@email.com", updateProfilePage.getEmail());
        Assert.assertEquals("test-user@localhost", updateProfilePage.getUsername());

        Assert.assertEquals("Username already exists.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileDuplicatedEmail() {
        doAIA();

        loginPage.login("test-user@localhost", "password");

        updateProfilePage.assertCurrent();

        updateProfilePage.update("New first", "New last", "keycloak-user@localhost", "test-user@localhost");

        updateProfilePage.assertCurrent();

        // assert that form holds submitted values during validation error
        Assert.assertEquals("New first", updateProfilePage.getFirstName());
        Assert.assertEquals("New last", updateProfilePage.getLastName());
        Assert.assertEquals("keycloak-user@localhost", updateProfilePage.getEmail());

        Assert.assertEquals("Email already exists.", updateProfilePage.getError());

        events.assertEmpty();
    }

    @Test
    public void updateProfileExpiredCookies() {
        doAIA();
        loginPage.login("john-doh@localhost", "password");

        updateProfilePage.assertCurrent();

        // Expire cookies and assert the page with "back to application" link present
        driver.manage().deleteAllCookies();

        updateProfilePage.update("New first", "New last", "keycloak-user@localhost", "test-user@localhost");
        errorPage.assertCurrent();

        String backToAppLink = errorPage.getBackToApplicationLink();

        ClientRepresentation client = ApiUtil.findClientByClientId(adminClient.realm("test"), "test-app").toRepresentation();
        Assert.assertEquals(backToAppLink, client.getBaseUrl());
    }

}
