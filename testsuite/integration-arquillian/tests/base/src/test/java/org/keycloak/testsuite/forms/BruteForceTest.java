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
package org.keycloak.testsuite.forms;

import org.jboss.arquillian.graphene.page.Page;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.Constants;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.AssertEvents.ExpectedEvent;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.AppPage.RequestType;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.LoginPasswordResetPage;
import org.keycloak.testsuite.pages.LoginTotpPage;
import org.keycloak.testsuite.pages.RegisterPage;
import org.keycloak.testsuite.util.GreenMailRule;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.RealmRepUtil;
import org.keycloak.testsuite.util.UserBuilder;

import java.net.MalformedURLException;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 * @author Stan Silvert ssilvert@redhat.com (C) 2016 Red Hat Inc.
 */
public class BruteForceTest extends AbstractTestRealmKeycloakTest {

    private static String userId;

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
        UserRepresentation user = RealmRepUtil.findUser(testRealm, "test-user@localhost");
        UserBuilder.edit(user).totpSecret("totpSecret");

        testRealm.setBruteForceProtected(true);
        testRealm.setFailureFactor(2);
        testRealm.setMaxDeltaTimeSeconds(20);
        testRealm.setMaxFailureWaitSeconds(100);
        testRealm.setWaitIncrementSeconds(5);
        //testRealm.setQuickLoginCheckMilliSeconds(0L);

        userId = user.getId();

        RealmRepUtil.findClientByClientId(testRealm, "test-app").setDirectAccessGrantsEnabled(true);
        testRealm.getUsers().add(UserBuilder.create().username("user2").email("user2@localhost").password("password").build());
    }

    @Before
    public void config() {
        try {
            clearUserFailures();
            clearAllUserFailures();
            RealmRepresentation realm = adminClient.realm("test").toRepresentation();
            realm.setFailureFactor(2);
            realm.setMaxDeltaTimeSeconds(20);
            realm.setMaxFailureWaitSeconds(100);
            realm.setWaitIncrementSeconds(5);
            adminClient.realm("test").update(realm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        events.clear();

    }

    @After
    public void slowItDown() throws Exception {
        try {
            clearUserFailures();
            clearAllUserFailures();
            RealmRepresentation realm = adminClient.realm("test").toRepresentation();
            realm.setMaxFailureWaitSeconds(900);
            realm.setMinimumQuickLoginWaitSeconds(60);
            realm.setWaitIncrementSeconds(60);
            realm.setQuickLoginCheckMilliSeconds(1000L);
            realm.setMaxDeltaTimeSeconds(60 * 60 * 12); // 12 hours
            realm.setFailureFactor(30);
            adminClient.realm("test").update(realm);
            testingClient.testing().setTimeOffset(Collections.singletonMap("offset", String.valueOf(0)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        events.clear();
        Thread.sleep(100);
    }


    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Rule
    public GreenMailRule greenMail = new GreenMailRule();

    @Page
    protected AppPage appPage;

    @Page
    protected LoginPage loginPage;

    @Page
    protected LoginPasswordResetPage resetPasswordPage;

    @Page
    private RegisterPage registerPage;

    @Page
    protected LoginTotpPage loginTotpPage;

    private TimeBasedOTP totp = new TimeBasedOTP();

    private int lifespan;

    @Before
    public void before() throws MalformedURLException {
        totp = new TimeBasedOTP();
    }

    public String getAdminToken() throws Exception {
        String clientId = Constants.ADMIN_CLI_CLIENT_ID;
        return oauth.doGrantAccessTokenRequest("master", "admin", "admin", null, clientId, null).getAccessToken();
    }

    public OAuthClient.AccessTokenResponse getTestToken(String password, String totp) throws Exception {
        return oauth.doGrantAccessTokenRequest("test", "test-user@localhost", password, totp, oauth.getClientId(), "password");

    }

    protected void clearUserFailures() throws Exception {
        adminClient.realm("test").attackDetection().clearBruteForceForUser(findUser("test-user@localhost").getId());
    }

    protected void clearAllUserFailures() throws Exception {
        adminClient.realm("test").attackDetection().clearAllBruteForce();
    }

    @Test
    public void testGrantInvalidPassword() throws Exception {
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNotNull(response.getAccessToken());
            Assert.assertNull(response.getError());
            events.clear();
        }
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("invalid", totpSecret);
            Assert.assertNull(response.getAccessToken());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals(response.getErrorDescription(), "Invalid user credentials");
            events.clear();
        }
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("invalid", totpSecret);
            Assert.assertNull(response.getAccessToken());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals(response.getErrorDescription(), "Invalid user credentials");
            events.clear();
        }
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNull(response.getAccessToken());
            Assert.assertNotNull(response.getError());
            Assert.assertEquals("invalid_grant", response.getError());
            Assert.assertEquals("Invalid user credentials", response.getErrorDescription());
            assertUserDisabledEvent();
            events.clear();
        }
        clearUserFailures();
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNotNull(response.getAccessToken());
            Assert.assertNull(response.getError());
            events.clear();
        }

    }

    @Test
    public void testGrantInvalidOtp() throws Exception {
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNotNull(response.getAccessToken());
            Assert.assertNull(response.getError());
            events.clear();
        }
        {
            OAuthClient.AccessTokenResponse response = getTestToken("password", "shite");
            Assert.assertNull(response.getAccessToken());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals(response.getErrorDescription(), "Invalid user credentials");
            events.clear();
        }
        {
            OAuthClient.AccessTokenResponse response = getTestToken("password", "shite");
            Assert.assertNull(response.getAccessToken());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals(response.getErrorDescription(), "Invalid user credentials");
            events.clear();
        }
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            assertTokenNull(response);
            Assert.assertNotNull(response.getError());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals("Invalid user credentials", response.getErrorDescription());
            assertUserDisabledEvent();
            events.clear();
        }
        clearUserFailures();
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNotNull(response.getAccessToken());
            Assert.assertNull(response.getError());
            events.clear();
        }

    }

    public void assertTokenNull(OAuthClient.AccessTokenResponse response) {
        Assert.assertNull(response.getAccessToken());
    }

    @Test
    public void testGrantMissingOtp() throws Exception {
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNotNull(response.getAccessToken());
            Assert.assertNull(response.getError());
            events.clear();
        }
        {
            OAuthClient.AccessTokenResponse response = getTestToken("password", null);
            Assert.assertNull(response.getAccessToken());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals(response.getErrorDescription(), "Invalid user credentials");
            events.clear();
        }
        {
            OAuthClient.AccessTokenResponse response = getTestToken("password", null);
            Assert.assertNull(response.getAccessToken());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals(response.getErrorDescription(), "Invalid user credentials");
            events.clear();
        }
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            assertTokenNull(response);
            Assert.assertNotNull(response.getError());
            Assert.assertEquals(response.getError(), "invalid_grant");
            Assert.assertEquals("Invalid user credentials", response.getErrorDescription());
            assertUserDisabledEvent();
            events.clear();
        }
        clearUserFailures();
        {
            String totpSecret = totp.generateTOTP("totpSecret");
            OAuthClient.AccessTokenResponse response = getTestToken("password", totpSecret);
            Assert.assertNotNull(response.getAccessToken());
            Assert.assertNull(response.getError());
            events.clear();
        }

    }

    @Test
    public void testBrowserInvalidPassword() throws Exception {
        loginSuccess();
        loginInvalidPassword();
        loginInvalidPassword();
        expectTemporarilyDisabled();
        expectTemporarilyDisabled("test-user@localhost", null, "invalid");
        clearUserFailures();
        loginSuccess();
        loginInvalidPassword();
        loginInvalidPassword();
        expectTemporarilyDisabled();
        expectTemporarilyDisabled("test-user@localhost", null, "invalid");
        clearAllUserFailures();
        loginSuccess();
    }

    @Test
    public void testWait() throws Exception {
        loginSuccess();
        loginInvalidPassword();
        loginInvalidPassword();
        expectTemporarilyDisabled();
        expectTemporarilyDisabled("test-user@localhost", null, "invalid");

        // KEYCLOAK-5420
        // Test to make sure that temporarily disabled doesn't increment failure count
        testingClient.testing().setTimeOffset(Collections.singletonMap("offset", String.valueOf(6)));
        // should be unlocked now
        loginSuccess();
        clearUserFailures();
        clearAllUserFailures();
        loginSuccess();
        testingClient.testing().setTimeOffset(Collections.singletonMap("offset", String.valueOf(0)));
    }

    @Test
    public void testBrowserInvalidPasswordDifferentCase() throws Exception {
        loginSuccess("test-user@localhost");
        loginInvalidPassword("test-User@localhost");
        loginInvalidPassword("Test-user@localhost");
        expectTemporarilyDisabled();
        expectTemporarilyDisabled("test-user@localhost", null, "invalid");
        clearAllUserFailures();
    }

    @Test
    public void testEmail() throws Exception {
        String userId = adminClient.realm("test").users().search("user2", null, null, null, 0, 1).get(0).getId();

        loginSuccess("user2@localhost");
        loginInvalidPassword("user2@localhost");
        loginInvalidPassword("user2@localhost");
        expectTemporarilyDisabled("user2@localhost", userId);
        clearAllUserFailures();
    }

    @Test
    public void testBrowserMissingPassword() throws Exception {
        loginSuccess();
        loginMissingPassword();
        loginMissingPassword();
        loginSuccess();
    }

    @Test
    public void testBrowserInvalidTotp() throws Exception {
        loginSuccess();
        loginInvalidPassword();
        loginWithTotpFailure();
        continueLoginWithCorrectTotpExpectFailure();
        continueLoginWithInvalidTotp();
        clearUserFailures();
        continueLoginWithTotp();
    }

    @Test
    public void testTotpGoingBack() throws Exception {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        continueLoginWithInvalidTotp();
        loginTotpPage.cancel();
        loginPage.assertCurrent();
        loginPage.login("test-user@localhost", "password");
        continueLoginWithInvalidTotp();
        continueLoginWithCorrectTotpExpectFailure();
        clearUserFailures();
        continueLoginWithTotp();
    }

    @Test
    public void testBrowserMissingTotp() throws Exception {
        loginSuccess();
        loginWithMissingTotp();
        loginWithMissingTotp();
        continueLoginWithMissingTotp();
        continueLoginWithCorrectTotpExpectFailure();
        // wait to unlock
        testingClient.testing().setTimeOffset(Collections.singletonMap("offset", String.valueOf(6)));

        continueLoginWithTotp();

        testingClient.testing().setTimeOffset(Collections.singletonMap("offset", String.valueOf(0)));
    }

    @Test
    public void testPermanentLockout() throws Exception {
        RealmRepresentation realm = testRealm().toRepresentation();

        try {
            // arrange
            realm.setPermanentLockout(true);
            testRealm().update(realm);

            // act
            loginInvalidPassword();
            loginInvalidPassword();

            // assert
            expectPermanentlyDisabled();
            Assert.assertFalse(adminClient.realm("test").users().search("test-user@localhost", 0, 1).get(0).isEnabled());
        } finally {
            realm.setPermanentLockout(false);
            testRealm().update(realm);
            UserRepresentation user = adminClient.realm("test").users().search("test-user@localhost", 0, 1).get(0);
            user.setEnabled(true);
            updateUser(user);
        }
    }

    @Test
    public void testResetLoginFailureCount() throws Exception {
        RealmRepresentation realm = testRealm().toRepresentation();

        try {
            // arrange
            realm.setPermanentLockout(true);
            testRealm().update(realm);

            // act
            loginInvalidPassword();
            loginSuccess();
            loginInvalidPassword();
            loginSuccess();

            // assert
            Assert.assertTrue(adminClient.realm("test").users().search("test-user@localhost", 0, 1).get(0).isEnabled());
        } finally {
            realm.setPermanentLockout(false);
            testRealm().update(realm);
        }
    }

    @Test
    public void testNonExistingAccounts() throws Exception {

        loginInvalidPassword("non-existent-user");
        loginInvalidPassword("non-existent-user");
        loginInvalidPassword("non-existent-user");

        registerUser("non-existent-user");

    }

    @Test
    public void testResetPassword() throws Exception {
        String userId = adminClient.realm("test").users().search("test-user@localhost", null, null, null, 0, 1).get(0).getId();

        loginSuccess();
        loginInvalidPassword();
        loginInvalidPassword();
        expectTemporarilyDisabled();
        expectTemporarilyDisabled("test-user@localhost", null, "invalid");

        loginPage.resetPassword();
        resetPasswordPage.assertCurrent();
        resetPasswordPage.changePassword("test-user@localhost");
        loginPage.assertCurrent();

        assertEquals("You should receive an email shortly with further instructions.", loginPage.getSuccessMessage());

        events.expectRequiredAction(EventType.RESET_PASSWORD_ERROR).user(userId);
        events.clear();
        clearUserFailures();
    }

    public void expectTemporarilyDisabled() throws Exception {
        expectTemporarilyDisabled("test-user@localhost", null, "password");
    }

    public void expectTemporarilyDisabled(String username, String userId) throws Exception {
        expectTemporarilyDisabled(username, userId, "password");
    }

    public void expectTemporarilyDisabled(String username, String userId, String password) throws Exception {
        loginPage.open();
        loginPage.login(username, password);

        loginPage.assertCurrent();
        String src = driver.getPageSource();
        Assert.assertEquals("Invalid username or password.", loginPage.getError());
        ExpectedEvent event = events.expectLogin()
                .session((String) null)
                .error(Errors.USER_TEMPORARILY_DISABLED)
                .detail(Details.USERNAME, username)
                .removeDetail(Details.CONSENT);
        if (userId != null) {
            event.user(userId);
        }
        event.assertEvent();
    }

    public void expectPermanentlyDisabled() throws Exception {
        expectPermanentlyDisabled("test-user@localhost", null);
    }

    public void expectPermanentlyDisabled(String username, String userId) throws Exception {
        loginPage.open();
        loginPage.login(username, "password");

        loginPage.assertCurrent();
        Assert.assertEquals("Account is disabled, contact your administrator.", loginPage.getError());
        ExpectedEvent event = events.expectLogin()
            .session((String) null)
            .error(Errors.USER_DISABLED)
            .detail(Details.USERNAME, username)
            .removeDetail(Details.CONSENT);
        if (userId != null) {
            event.user(userId);
        }
        event.assertEvent();
    }

    public void loginSuccess() throws Exception {
        loginSuccess("test-user@localhost");
    }

    public void loginSuccess(String username) throws Exception {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        loginTotpPage.assertCurrent();

        String totpSecret = totp.generateTOTP("totpSecret");
        loginTotpPage.login(totpSecret);

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().assertEvent();

        appPage.logout();
        events.clear();


    }

    public void loginWithTotpFailure() {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        loginTotpPage.assertCurrent();

        loginTotpPage.login("123456");
        loginTotpPage.assertCurrent();
        Assert.assertEquals("Invalid authenticator code.", loginPage.getError());
        events.clear();
    }

    public void continueLoginWithTotp() {
        loginTotpPage.assertCurrent();

        String totpSecret = totp.generateTOTP("totpSecret");
        loginTotpPage.login(totpSecret);

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().assertEvent();
        appPage.logout();
        events.clear();
    }

    public void continueLoginWithCorrectTotpExpectFailure() {
        loginTotpPage.assertCurrent();

        String totpSecret = totp.generateTOTP("totpSecret");
        loginTotpPage.login(totpSecret);

        loginTotpPage.assertCurrent();
        Assert.assertEquals("Invalid authenticator code.", loginTotpPage.getError());

        events.clear();
    }

    public void continueLoginWithInvalidTotp() {
        loginTotpPage.assertCurrent();

        loginTotpPage.login("123456");

        loginTotpPage.assertCurrent();
        Assert.assertEquals("Invalid authenticator code.", loginTotpPage.getError());
        events.clear();
    }

    public void continueLoginWithMissingTotp() {
        loginTotpPage.assertCurrent();

        loginTotpPage.login(null);

        loginTotpPage.assertCurrent();
        Assert.assertEquals("Invalid authenticator code.", loginTotpPage.getError());
        events.clear();
    }

    public void loginWithMissingTotp() throws Exception {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        loginTotpPage.assertCurrent();

        loginTotpPage.login(null);
        loginTotpPage.assertCurrent();
        Assert.assertEquals("Invalid authenticator code.", loginPage.getError());

        events.clear();
    }

    public void loginInvalidPassword() throws Exception {
        loginInvalidPassword("test-user@localhost");
    }

    public void loginInvalidPassword(String username) throws Exception {
        loginPage.open();
        loginPage.login(username, "invalid");

        loginPage.assertCurrent();

        Assert.assertEquals("Invalid username or password.", loginPage.getError());

        events.clear();
    }

    public void loginMissingPassword() {
        loginPage.open();
        loginPage.missingPassword("test-user@localhost");

        loginPage.assertCurrent();

        Assert.assertEquals("Invalid username or password.", loginPage.getError());
        events.clear();
    }

    public void registerUser(String username) {
        loginPage.open();
        loginPage.clickRegister();
        registerPage.assertCurrent();

        registerPage.register("user", "name", username + "@localhost", username, "password", "password");

        Assert.assertNull(registerPage.getInstruction());

        events.clear();
    }

    private void assertUserDisabledEvent() {
        events.expect(EventType.LOGIN_ERROR).error(Errors.USER_TEMPORARILY_DISABLED).assertEvent();
    }
}
