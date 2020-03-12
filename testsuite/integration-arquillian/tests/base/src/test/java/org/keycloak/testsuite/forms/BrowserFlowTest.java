package org.keycloak.testsuite.forms;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.authentication.AuthenticationFlow;
import org.keycloak.authentication.authenticators.browser.OTPFormAuthenticatorFactory;
import org.keycloak.authentication.authenticators.browser.PasswordFormFactory;
import org.keycloak.authentication.authenticators.browser.UsernameFormFactory;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.authentication.authenticators.browser.WebAuthnAuthenticatorFactory;
import org.keycloak.authentication.authenticators.conditional.ConditionalRoleAuthenticatorFactory;
import org.keycloak.authentication.authenticators.conditional.ConditionalUserConfiguredAuthenticatorFactory;
import org.keycloak.authentication.requiredactions.WebAuthnRegisterFactory;
import org.keycloak.events.Details;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.Constants;
import org.keycloak.models.utils.DefaultAuthenticationFlows;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderSimpleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.ActionURIUtils;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.admin.authentication.AbstractAuthenticationTest;
import org.keycloak.testsuite.auth.page.login.OneTimeCode;
import org.keycloak.testsuite.broker.SocialLoginTest;
import org.keycloak.testsuite.client.KeycloakTestingClient;
import org.keycloak.testsuite.model.ClientModelTest;
import org.keycloak.testsuite.pages.ErrorPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.LoginTotpPage;
import org.keycloak.testsuite.pages.LoginUsernameOnlyPage;
import org.keycloak.testsuite.pages.PasswordPage;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;
import org.keycloak.testsuite.util.FlowUtil;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.URLUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.keycloak.testsuite.admin.AbstractAdminTest.loadJson;
import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;
import static org.keycloak.testsuite.broker.SocialLoginTest.Provider.GITHUB;
import static org.keycloak.testsuite.broker.SocialLoginTest.Provider.GITLAB;
import static org.keycloak.testsuite.broker.SocialLoginTest.Provider.GOOGLE;

public class BrowserFlowTest extends AbstractTestRealmKeycloakTest {
    private static final String INVALID_AUTH_CODE = "Invalid authenticator code.";

    @ArquillianResource
    protected OAuthClient oauth;

    @Drone
    protected WebDriver driver;

    @Page
    protected LoginPage loginPage;

    @Page
    protected LoginUsernameOnlyPage loginUsernameOnlyPage;

    @Page
    protected PasswordPage passwordPage;

    @Page
    protected ErrorPage errorPage;

    @Page
    protected LoginTotpPage loginTotpPage;

    @Page
    protected OneTimeCode oneTimeCodePage;

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, ClientModelTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.model");
    }

    private RealmRepresentation loadTestRealm() {
        RealmRepresentation res = loadJson(getClass().getResourceAsStream("/testrealm.json"), RealmRepresentation.class);
        res.setBrowserFlow("browser");
        return res;
    }

    private void importTestRealm(Consumer<RealmRepresentation> realmUpdater) {
        RealmRepresentation realm = loadTestRealm();
        if (realmUpdater != null) {
            realmUpdater.accept(realm);
        }
        importRealm(realm);
    }

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        log.debug("Adding test realm for import from testrealm.json");
        testRealms.add(loadTestRealm());
    }

    private void provideUsernamePassword(String user) {
        // Go to login page
        loginPage.open();
        loginPage.assertCurrent();

        // Login attempt with an invalid password
        loginPage.login(user, "invalid");
        loginPage.assertCurrent();

        // Login attempt with a valid password - user with configured OTP
        loginPage.login(user, "password");
    }

    private String getOtpCode(String key) {
        return new TimeBasedOTP().generateTOTP(key);
    }

    @Test
    public void testUserWithoutAdditionalFactorConnection() {
        provideUsernamePassword("test-user@localhost");
        Assert.assertFalse(loginPage.isCurrent());
        Assert.assertFalse(oneTimeCodePage.isOtpLabelPresent());
        Assert.assertFalse(loginTotpPage.isCurrent());
        loginTotpPage.assertCredentialsComboboxAvailability(false);
    }

    @Test
    public void testUserWithOneAdditionalFactorOtpFails() {
        provideUsernamePassword("user-with-one-configured-otp");
        Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
        loginTotpPage.assertCurrent();
        loginTotpPage.assertCredentialsComboboxAvailability(false);

        oneTimeCodePage.sendCode("123456");
        Assert.assertEquals(INVALID_AUTH_CODE, oneTimeCodePage.getError());
        Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
    }

    @Test
    public void testUserWithOneAdditionalFactorOtpSuccess() {
        provideUsernamePassword("user-with-one-configured-otp");
        Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
        loginTotpPage.assertCurrent();
        loginTotpPage.assertCredentialsComboboxAvailability(false);

        oneTimeCodePage.sendCode(getOtpCode("DJmQfC73VGFhw7D4QJ8A"));
        Assert.assertFalse(loginPage.isCurrent());
        Assert.assertFalse(oneTimeCodePage.isOtpLabelPresent());
    }

    @Test
    public void testBackButton() {
        provideUsernamePassword("user-with-one-configured-otp");
        Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());

        // Assert "Back" button available on the TOTP page
        loginTotpPage.assertBackButtonAvailability(true);
        loginTotpPage.clickBackButton();

        // Assert "Back" button not available on the Browser page
        loginPage.assertCurrent();
        loginPage.assertBackButtonAvailability(false);

        // Login
        loginPage.login("user-with-one-configured-otp", "password");

        oneTimeCodePage.sendCode(getOtpCode("DJmQfC73VGFhw7D4QJ8A"));
        Assert.assertFalse(loginPage.isCurrent());
        Assert.assertFalse(oneTimeCodePage.isOtpLabelPresent());
    }

    @Test
    public void testUserWithTwoAdditionalFactors() {
        final String firstKey = "DJmQfC73VGFhw7D4QJ8A";
        final String secondKey = "ABCQfC73VGFhw7D4QJ8A";

        // Provide username and password
        provideUsernamePassword("user-with-two-configured-otp");
        Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
        loginTotpPage.assertCurrent();
        loginTotpPage.assertCredentialsComboboxAvailability(true);

        // Check that selected credential is "first"
        Assert.assertEquals("first", loginTotpPage.getSelectedCredential());

        // Select "second" factor but try to connect with the OTP code from the "first" one
        oneTimeCodePage.selectFactor("second");
        oneTimeCodePage.sendCode(getOtpCode(firstKey));
        Assert.assertEquals(INVALID_AUTH_CODE, oneTimeCodePage.getError());

        // Select "first" factor but try to connect with the OTP code from the "second" one
        oneTimeCodePage.selectFactor("first");
        oneTimeCodePage.sendCode(getOtpCode(secondKey));
        Assert.assertEquals(INVALID_AUTH_CODE, oneTimeCodePage.getError());

        // Select "second" factor and try to connect with its OTP code
        oneTimeCodePage.selectFactor("second");
        oneTimeCodePage.sendCode(getOtpCode(secondKey));
        Assert.assertFalse(oneTimeCodePage.isOtpLabelPresent());
    }

    private void testCredentialsOrder(String username, List<String> orderedCredentials) {
        // Provide username and password
        provideUsernamePassword(username);
        Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
        loginTotpPage.assertCurrent();
        loginTotpPage.assertCredentialsComboboxAvailability(true);

        // Check that preferred credential is selected
        Assert.assertEquals(orderedCredentials.get(0), loginTotpPage.getSelectedCredential());
        // Check credentials order
        List<String> creds = loginTotpPage.getAvailableCredentials();
        Assert.assertEquals(2, creds.size());
        Assert.assertEquals(orderedCredentials, creds);
    }

    @Test
    public void testCredentialsOrder() {
        String username = "user-with-two-configured-otp";
        int idxFirst = 0; // Credentials order is: first, password, second

        // Priority tells: first then second
        testCredentialsOrder(username, Arrays.asList("first", "second"));

        try {
            // Move first credential in last position
            importTestRealm(realmRep -> {
                UserRepresentation user = realmRep.getUsers().stream().filter(u -> username.equals(u.getUsername())).findFirst().get();
                // Move first OTP after second while priority are not used for import
                user.getCredentials().add(user.getCredentials().remove(idxFirst));
            });

            // Priority tells: second then first
            testCredentialsOrder(username, Arrays.asList("second", "first"));
        } finally {
            // Restore default testrealm.json
            importTestRealm(null);
        }
    }

    // In a sub-flow with alternative credential executors, check which credentials are available and in which order
    @Test
    public void testAlternativeCredentials() {
        try {
            configureBrowserFlowWithAlternativeCredentials();

            // test-user has not other credential than his password. No combobox is displayed
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.login("test-user@localhost");
            loginTotpPage.assertCredentialsComboboxAvailability(false);

            // A user with only one other credential than his password: the combobox should
            // let him choose between his password and his OTP credentials
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.login("user-with-one-configured-otp");
            loginTotpPage.assertCredentialsComboboxAvailability(true);
            Assert.assertEquals(Arrays.asList("Password", "OTP"), loginTotpPage.getAvailableCredentials());

            // A user with two other credentials than his password: the combobox should
            // let him choose between his 3 credentials in the order of his preferences
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.login("user-with-two-configured-otp");
            loginTotpPage.assertCredentialsComboboxAvailability(true);
            Assert.assertEquals("OTP - first", loginTotpPage.getSelectedCredential());
            Assert.assertEquals(Arrays.asList("OTP - first", "Password", "OTP - second"), loginTotpPage.getAvailableCredentials());
        } finally {
            revertFlows("browser - alternative");
        }
    }

    private void configureBrowserFlowWithAlternativeCredentials() {
        configureBrowserFlowWithAlternativeCredentials(testingClient);
    }

    static void configureBrowserFlowWithAlternativeCredentials(KeycloakTestingClient testingClient) {
        final String newFlowAlias = "browser - alternative";
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.CONDITIONAL, altSubFlow -> altSubFlow
                                // Add authenticators to this flow: 1 conditional authenticator and 2 basic authenticator executions
                                .addAuthenticatorExecution(Requirement.REQUIRED, ConditionalUserConfiguredAuthenticatorFactory.PROVIDER_ID)
                                .addAuthenticatorExecution(Requirement.ALTERNATIVE, PasswordFormFactory.PROVIDER_ID)
                                .addAuthenticatorExecution(Requirement.ALTERNATIVE, OTPFormAuthenticatorFactory.PROVIDER_ID)
                        )
                )
                .defineAsBrowserFlow()
        );
    }

    // In a form waiting for a username only, provides a username and check if password is requested in the following execution of the flow
    private boolean needsPassword(String username) {
        // provides username
        loginUsernameOnlyPage.open();
        loginUsernameOnlyPage.login(username);

        return passwordPage.isCurrent();
    }

    // A conditional flow without conditional authenticator should automatically be disabled
    @Test
    public void testFlowDisabledWhenConditionalAuthenticatorIsMissing() {
        try {
            configureBrowserFlowWithConditionalSubFlowHavingConditionalAuthenticator("browser - non missing conditional authenticator", true);
            Assert.assertTrue(needsPassword("user-with-two-configured-otp"));

            configureBrowserFlowWithConditionalSubFlowHavingConditionalAuthenticator("browser - missing conditional authenticator", false);
            // Flow is conditional but it is missing a conditional authentication executor
            // The whole flow is disabled
            Assert.assertFalse(needsPassword("user-with-two-configured-otp"));
        } finally {
            revertFlows("browser - non missing conditional authenticator");
        }
    }

    private void configureBrowserFlowWithConditionalSubFlowHavingConditionalAuthenticator(String newFlowAlias, boolean conditionFlowHasConditionalAuthenticator) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.CONDITIONAL, subFlow -> {
                            if (conditionFlowHasConditionalAuthenticator) {
                                // Add authenticators to this flow: 1 conditional authenticator and a basic authenticator executions
                                subFlow.addAuthenticatorExecution(Requirement.REQUIRED, ConditionalUserConfiguredAuthenticatorFactory.PROVIDER_ID);
                            }
                            // Update the browser forms only with a UsernameForm
                            subFlow.addAuthenticatorExecution(Requirement.REQUIRED, PasswordFormFactory.PROVIDER_ID);
                        }))
                .defineAsBrowserFlow()
        );
    }

    // Configure a conditional authenticator in a non-conditional sub-flow
    // In such case, the flow is evaluated and the conditional authenticator is considered as disabled
    @Test
    public void testConditionalAuthenticatorInNonConditionalFlow() {
        try {
            configureBrowserFlowWithConditionalAuthenticatorInNonConditionalFlow();

            // provides username
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.login("user-with-two-configured-otp");

            // if flow was conditional, the conditional authenticator would disable the flow because no user have the expected role
            // Here, the password form is shown: it shows that the executor of the conditional bloc has been disabled. Other
            // executors of this flow are executed anyway
            passwordPage.assertCurrent();
        } finally {
            revertFlows("browser - nonconditional");
        }
    }

    private void configureBrowserFlowWithConditionalAuthenticatorInNonConditionalFlow() {
        String newFlowAlias = "browser - nonconditional";
        String requiredRole = "non-existing-role";
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.REQUIRED, subFlow -> subFlow
                                // Add authenticators to this flow: 1 conditional authenticator and a basic authenticator executions
                                .addAuthenticatorExecution(Requirement.REQUIRED, ConditionalRoleAuthenticatorFactory.PROVIDER_ID,
                                        config -> config.getConfig().put("condUserRole", requiredRole))
                                .addAuthenticatorExecution(Requirement.REQUIRED, PasswordFormFactory.PROVIDER_ID)
                        )
                )
                .defineAsBrowserFlow()
        );
    }

    // Check the ConditionalRoleAuthenticator
    // Configure a conditional subflow with the required role "user" and an OTP authenticator
    // user-with-two-configured-otp has the "user" role and should be asked for an OTP code
    // user-with-one-configured-otp does not have the role. He should not be asked for an OTP code
    @Test
    public void testConditionalRoleAuthenticator() {
        String requiredRole = "user";
        // A browser flow is configured with an OTPForm for users having the role "user"
        configureBrowserFlowOTPNeedsRole(requiredRole);

        try {
            // user-with-two-configured-otp has been configured with role "user". He should be asked for an OTP code
            provideUsernamePassword("user-with-two-configured-otp");
            Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
            loginTotpPage.assertCurrent();
            loginTotpPage.assertCredentialsComboboxAvailability(true);

            // user-with-one-configured-otp has not configured role. He should not be asked for an OTP code
            provideUsernamePassword("user-with-one-configured-otp");
            Assert.assertFalse(oneTimeCodePage.isOtpLabelPresent());
            Assert.assertFalse(loginTotpPage.isCurrent());
        } finally {
            revertFlows("browser - rule");
        }
    }

    @Test
    public void testAlternativeNonInteractiveExecutorInSubflow() {
        final String newFlowAlias = "browser - alternative non-interactive executor";
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.REQUIRED, reqSubFlow -> reqSubFlow
                                .addAuthenticatorExecution(Requirement.ALTERNATIVE, PassThroughAuthenticator.PROVIDER_ID)
                        )
                )
                .defineAsBrowserFlow()
        );

        try {
            // provides username
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.login("test-user@localhost");

            // Check that Keycloak is redirecting us to the Keycloak account management page
            WebElement aHref = driver.findElement(By.tagName("a"));
            driver.get(aHref.getAttribute("href"));
            Assert.assertEquals("Keycloak Account Management", driver.getTitle());
        } finally {
            revertFlows("browser - alternative non-interactive executor");
        }
    }

    @Test
    public void testBackButtonFromAlternativeSubflow() {
        final String newFlowAlias = "browser - back button subflow";
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.REQUIRED, reqSubFlow -> reqSubFlow
                                // Add authenticators to this flow: 1 PASSWORD, 2 Another subflow with having only OTP as child
                                .addAuthenticatorExecution(Requirement.ALTERNATIVE, PasswordFormFactory.PROVIDER_ID)
                                .addSubFlowExecution("otp subflow", AuthenticationFlow.BASIC_FLOW, Requirement.ALTERNATIVE, altSubFlow -> altSubFlow
                                        .addAuthenticatorExecution(Requirement.REQUIRED, OTPFormAuthenticatorFactory.PROVIDER_ID)
                                )
                        )
                )
                .defineAsBrowserFlow()
        );

        try {
            // Provide username, should be on password page
            needsPassword("user-with-one-configured-otp");

            // Select the OTP subflow. The credential selection won't be on the page due it's subflow
            passwordPage.selectCredential("otp subflow");
            loginTotpPage.assertCurrent();
            loginTotpPage.assertCredentialsComboboxAvailability(false);

            // Click "back". Should be on password page
            loginTotpPage.clickBackButton();
            passwordPage.assertCurrent();
            passwordPage.login("password");

            Assert.assertFalse(passwordPage.isCurrent());
            Assert.assertFalse(loginPage.isCurrent());
            events.expectLogin().user(testRealm().users().search("user-with-one-configured-otp").get(0).getId())
                    .detail(Details.USERNAME, "user-with-one-configured-otp").assertEvent();
        } finally {
            revertFlows("browser - back button subflow");
        }
    }

    // Configure a flow with a conditional sub flow with a condition where a specific role is required
    private void configureBrowserFlowOTPNeedsRole(String requiredRole) {
        final String newFlowAlias = "browser - rule";
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        // Update the browser forms with a UsernamePasswordForm
                        .addAuthenticatorExecution(Requirement.REQUIRED, UsernamePasswordFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.CONDITIONAL, subFlow -> subFlow
                                .addAuthenticatorExecution(Requirement.REQUIRED, ConditionalRoleAuthenticatorFactory.PROVIDER_ID,
                                        config -> config.getConfig().put("condUserRole", requiredRole))
                                .addAuthenticatorExecution(Requirement.REQUIRED, OTPFormAuthenticatorFactory.PROVIDER_ID)
                        )
                )
                .defineAsBrowserFlow()
        );
    }

    @Test
    public void testSwitchExecutionNotAllowedWithRequiredPasswordAndAlternativeOTP() {
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredPasswordFormAndAlternativeOTP(newFlowAlias);

        try {
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.assertCurrent();
            loginUsernameOnlyPage.login("user-with-one-configured-otp");

            // Assert on password page now
            passwordPage.assertCurrent();

            String otpAuthenticatorExecutionId = realmsResouce().realm("test").flows().getExecutions(newFlowAlias)
                    .stream()
                    .filter(execution -> OTPFormAuthenticatorFactory.PROVIDER_ID.equals(execution.getProviderId()))
                    .findFirst()
                    .get()
                    .getId();

            // Manually run request to switch execution to OTP. It shouldn't be allowed and error should be thrown
            String actionURL = ActionURIUtils.getActionURIFromPageSource(driver.getPageSource());
            String formParameters = Constants.AUTHENTICATION_EXECUTION + "=" + otpAuthenticatorExecutionId + "&"
                    + Constants.CREDENTIAL_ID + "=";

            URLUtils.sendPOSTRequestWithWebDriver(actionURL, formParameters);

            errorPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }


    @Test
    public void testSocialProvidersPresentOnLoginUsernameOnlyPageIfConfigured() {
        String testRealm = "test";
        // Test setup - Configure the testing Keycloak instance with UsernameForm & PasswordForm (both REQUIRED) and OTPFormAuthenticator (ALTERNATIVE)
        configureBrowserFlowWithRequiredPasswordFormAndAlternativeOTP("browser - copy 1");

        try {
            SocialLoginTest socialLoginTest = new SocialLoginTest();

            // Add some sample dummy GitHub, Gitlab & Google social providers to the testing realm. Dummy because they won't be fully
            // functional (won't have proper Client ID & Client Secret defined). But that doesn't matter for this particular test. What
            // matters is if they are visible (clickable) on the LoginUsernameOnlyPage once the page is loaded
            for (SocialLoginTest.Provider provider : Arrays.asList(GITHUB, GITLAB, GOOGLE)) {
                adminClient.realm(testRealm).identityProviders().create(socialLoginTest.buildIdp(provider));

                loginUsernameOnlyPage.open();
                loginUsernameOnlyPage.assertCurrent();
                // For each of the testing social providers, check the particular social provider button is present on the UsernameForm
                // Test succeeded if NoSuchElementException is thrown for none of them
                loginUsernameOnlyPage.findSocialButton(provider.id());
            }

            // Test cleanup - Return back to the initial state
        } finally {
            // Drop the testing social providers previously created within the test
            for (IdentityProviderRepresentation providerRepresentation : adminClient.realm(testRealm).identityProviders().findAll()) {
                adminClient.realm(testRealm).identityProviders().get(providerRepresentation.getInternalId()).remove();
            }

            revertFlows("browser - copy 1");
        }
    }

    // Configure the browser flow with those 3 authenticators at same level as subflows of the "Form":
    // UsernameForm: REQUIRED
    // PasswordForm: REQUIRED
    // OTPFormAuthenticator: ALTERNATIVE
    // In reality, the configuration of the flow like this doesn't have much sense, but nothing prevents administrator to configure it at this moment
    private void configureBrowserFlowWithRequiredPasswordFormAndAlternativeOTP(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        // Add REQUIRED UsernameForm Authenticator as first
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        // Add REQUIRED PasswordForm Authenticator as second
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, PasswordFormFactory.PROVIDER_ID)
                        // Add OTPForm ALTERNATIVE execution as third
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.ALTERNATIVE, OTPFormAuthenticatorFactory.PROVIDER_ID)
                )
                // Activate this new flow
                .defineAsBrowserFlow()
        );
    }

    @Test
    public void testConditionalFlowWithConditionalAuthenticatorEvaluatingToFalseActsAsDisabled(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithConditionalFlowWithOTP(newFlowAlias);

        try {
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.assertCurrent();
            loginUsernameOnlyPage.login("test-user@localhost");

            // Assert that the login evaluates to an error, as all required elements to not validate to successful
            errorPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }

    @Test
    public void testConditionalFlowWithConditionalAuthenticatorEvaluatingToTrueActsAsRequired(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithConditionalFlowWithOTP(newFlowAlias);

        try {
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.assertCurrent();
            loginUsernameOnlyPage.login("user-with-one-configured-otp");

            // Assert on password page now
            Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
            loginTotpPage.assertCurrent();
            loginTotpPage.assertCredentialsComboboxAvailability(false);

            oneTimeCodePage.sendCode(getOtpCode("DJmQfC73VGFhw7D4QJ8A"));
            Assert.assertFalse(loginTotpPage.isCurrent());
            events.expectLogin().user(testRealm().users().search("user-with-one-configured-otp").get(0).getId())
                    .detail(Details.USERNAME, "user-with-one-configured-otp").assertEvent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }

    /**
     * Configure the browser flow with a simple flow that contains:
     * UsernameForm REQUIRED
     * Subflow REQUIRED
     * ** Sub-subflow CONDITIONAL
     * ***** ConditionalUserConfiguredAuthenticator REQUIRED
     * ***** OTPFormAuthenticator REQUIRED
     *
     * The expected behaviour is to prevent login if the user doesn't have an OTP credential, and to be able to login with an
     * OTP if the user has one. This demonstrates that conditional branches act as disabled when their conditional authenticator evaluates to false
     *
     * @param newFlowAlias
     */
    private void configureBrowserFlowWithConditionalFlowWithOTP(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.REQUIRED, sf -> sf
                                .addSubFlowExecution(Requirement.CONDITIONAL, subFlow -> subFlow
                                        .addAuthenticatorExecution(Requirement.REQUIRED, ConditionalUserConfiguredAuthenticatorFactory.PROVIDER_ID)
                                        .addAuthenticatorExecution(Requirement.REQUIRED, OTPFormAuthenticatorFactory.PROVIDER_ID)
                                )
                        )

                )
                // Activate this new flow
                .defineAsBrowserFlow()
        );
    }

    /**
     * In this test the user is expected to have to log in with OTP
     */
    @Test
    public void testConditionalFlowWithMultipleConditionalAuthenticatorsWithUserWithRoleAndOTP() {
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithConditionalFlowWithMultipleConditionalAuthenticators(newFlowAlias);

        try {
            String userId = testRealm().users().search("user-with-two-configured-otp").get(0).getId();
            provideUsernamePassword("user-with-two-configured-otp");
            events.expectLogin().user(userId).session((String) null)
                    .error("invalid_user_credentials")
                    .detail(Details.USERNAME, "user-with-two-configured-otp")
                    .removeDetail(Details.CONSENT)
                    .assertEvent();

            // Assert on otp page now
            Assert.assertTrue(oneTimeCodePage.isOtpLabelPresent());
            loginTotpPage.assertCurrent();
            loginTotpPage.assertCredentialsComboboxAvailability(true);

            oneTimeCodePage.sendCode(getOtpCode("DJmQfC73VGFhw7D4QJ8A"));
            Assert.assertFalse(loginTotpPage.isCurrent());
            events.expectLogin().user(userId).detail(Details.USERNAME, "user-with-two-configured-otp").assertEvent();
        } finally {
            revertFlows("browser - copy 1");
        }
    }

    /**
     * In this test, the user is expected to have to login with username and password only, as the conditional branch evaluates to false, and is therefore DISABLED
     */
    @Test
    public void testConditionalFlowWithMultipleConditionalAuthenticatorsWithUserWithRoleButNotOTP() {
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithConditionalFlowWithMultipleConditionalAuthenticators(newFlowAlias);

        try {
            String userId = testRealm().users().search("user-with-one-configured-otp").get(0).getId();
            provideUsernamePassword("user-with-one-configured-otp");
            events.expectLogin().user(userId).session((String) null)
                    .error("invalid_user_credentials")
                    .detail(Details.USERNAME, "user-with-one-configured-otp")
                    .removeDetail(Details.CONSENT)
                    .assertEvent();
            // Assert not on otp page now
            Assert.assertFalse(oneTimeCodePage.isOtpLabelPresent());
            Assert.assertFalse(loginTotpPage.isCurrent());
            events.expectLogin().user(userId).detail(Details.USERNAME, "user-with-one-configured-otp").assertEvent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }

    /**
     * Configure the browser flow with a flow that contains:
     * UsernamePasswordForm REQUIRED
     * Subflow CONDITIONAL
     * ** ConditionalUserConfiguredAuthenticator REQUIRED
     * ** ConditionalRoleAuthenticator REQUIRED
     * ** OTPFormAuthenticatorFactory ALTERNATIVE
     * ** sub-subflow ALERNATIVE
     * **** OTPFormAuthenticatorFactory DISABLED
     *
     * The expected behaviour is the following:
     * - If the user is in the "user" group and has an OTP credential -> he sees the OTP form
     * - Otherwise the user logs in directly
     * This is important, because the ConditionalRoleAuthenticator must not count towards the check from the ConditionalUserConfiguredAuthenticator
     * The sub-subflow is present in the conditional flow to show that it is ignored by the ConditionalUserConfiguredAuthenticator (as it would raise an exception otherwise)
     * @param newFlowAlias
     */
    private void configureBrowserFlowWithConditionalFlowWithMultipleConditionalAuthenticators(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                                .clear()
                                .addAuthenticatorExecution(Requirement.REQUIRED, UsernamePasswordFormFactory.PROVIDER_ID)
                                .addSubFlowExecution(Requirement.CONDITIONAL, subFlow -> subFlow
                                        .addAuthenticatorExecution(Requirement.REQUIRED, ConditionalUserConfiguredAuthenticatorFactory.PROVIDER_ID)
                                        .addAuthenticatorExecution(Requirement.REQUIRED, ConditionalRoleAuthenticatorFactory.PROVIDER_ID,
                                                config -> config.getConfig().put("condUserRole", "user"))
                                        .addAuthenticatorExecution(Requirement.ALTERNATIVE, OTPFormAuthenticatorFactory.PROVIDER_ID)
                                        .addSubFlowExecution(Requirement.ALTERNATIVE, sf -> sf
                                                .addAuthenticatorExecution(Requirement.DISABLED, OTPFormAuthenticatorFactory.PROVIDER_ID))
                                )
                        // Activate this new flow
                ).defineAsBrowserFlow()
        );
    }

    /**
     * This test checks that if a REQUIRED authentication execution which has isUserSetupAllowed -> true
     * has its requiredActionProvider in a not registered state, then it will not try to create the required action,
     * and will instead raise an credential setup required error.
     */
    @Test
    public void testLoginWithWithNoOTPCredentialAndNoRequiredActionProviderRegistered(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredOTP(newFlowAlias);
        RequiredActionProviderRepresentation otpRequiredAction = testRealm().flows().getRequiredAction("CONFIGURE_TOTP");
        testRealm().flows().removeRequiredAction("CONFIGURE_TOTP");
        try {
            provideUsernamePassword("test-user@localhost");

            // Assert that the login evaluates to an error, as all required elements to not validate to successful
            errorPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
            RequiredActionProviderSimpleRepresentation simpleRepresentation = new RequiredActionProviderSimpleRepresentation();
            simpleRepresentation.setProviderId("CONFIGURE_TOTP");
            simpleRepresentation.setName(otpRequiredAction.getName());
            testRealm().flows().registerRequiredAction(simpleRepresentation);
        }
    }

    /**
     * This test checks that if a REQUIRED authentication execution which has isUserSetupAllowed -> true
     * has its requiredActionProvider disabled, then it will not try to create the required action,
     * and will instead raise an credential setup required error.
     */
    @Test
    public void testLoginWithWithNoOTPCredentialAndRequiredActionProviderDisabled(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredOTP(newFlowAlias);
        RequiredActionProviderRepresentation otpRequiredAction = testRealm().flows().getRequiredAction("CONFIGURE_TOTP");
        otpRequiredAction.setEnabled(false);
        testRealm().flows().updateRequiredAction("CONFIGURE_TOTP", otpRequiredAction);
        try {
            provideUsernamePassword("test-user@localhost");

            // Assert that the login evaluates to an error, as all required elements to not validate to successful
            errorPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
            otpRequiredAction.setEnabled(true);
            testRealm().flows().updateRequiredAction("CONFIGURE_TOTP", otpRequiredAction);
        }
    }

    /**
     * This test checks that if a REQUIRED authentication execution which has isUserSetupAllowed -> true
     * has its requiredActionProvider enabled, than it will login and show the otpSetup page.
     */
    @Test
    public void testLoginWithWithNoOTPCredential(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredOTP(newFlowAlias);;
        try {
            provideUsernamePassword("test-user@localhost");

            // Assert that in this case you arrive to an OTP setup
            Assert.assertTrue(driver.getCurrentUrl().contains("required-action?execution=CONFIGURE_TOTP"));

        } finally {
            revertFlows("browser - copy 1");
            UserRepresentation user = testRealm().users().search("test-user@localhost").get(0);
            user.setRequiredActions(Collections.emptyList());
            testRealm().users().get(user.getId()).update(user);
        }
    }

    /**
     * This flow contains:
     * UsernamePasswordForm REQUIRED
     * OTPForm REQUIRED
     *
     * @param newFlowAlias
     */
    private void configureBrowserFlowWithRequiredOTP(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                                .clear()
                                .addAuthenticatorExecution(Requirement.REQUIRED, UsernamePasswordFormFactory.PROVIDER_ID)
                                .addAuthenticatorExecution(Requirement.REQUIRED, OTPFormAuthenticatorFactory.PROVIDER_ID)
                ).defineAsBrowserFlow() // Activate this new flow
        );
    }

    /**
     * This test checks that if a REQUIRED authentication execution which has isUserSetupAllowed -> true
     * has its requiredActionProvider in a not registered state, then it will not try to create the required action,
     * and will instead raise an credential setup required error.
     * NOTE: webauthn currently isn't configured by default in the realm. When this changes, this test will need to be adapted
     */
    @Test
    public void testLoginWithWithNoWebAuthnCredentialAndNoRequiredActionProviderRegistered(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredWebAuthn(newFlowAlias);
        try {
            provideUsernamePassword("test-user@localhost");

            // Assert that the login evaluates to an error, as all required elements to not validate to successful
            errorPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }

    /**
     * This test checks that if a REQUIRED authentication execution which has isUserSetupAllowed -> true
     * has its requiredActionProvider disabled, then it will not try to create the required action,
     * and will instead raise an credential setup required error.
     * NOTE: webauthn currently isn't configured by default in the realm. When this changes, this test will need to be adapted
     */
    @Test
    public void testLoginWithWithNoWebAuthnCredentialAndRequiredActionProviderDisabled(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredWebAuthn(newFlowAlias);
        RequiredActionProviderSimpleRepresentation requiredActionRepresentation = new RequiredActionProviderSimpleRepresentation();
        requiredActionRepresentation.setName("WebAuthn Required Action");
        requiredActionRepresentation.setProviderId(WebAuthnRegisterFactory.PROVIDER_ID);
        testRealm().flows().registerRequiredAction(requiredActionRepresentation);
        RequiredActionProviderRepresentation rapr = testRealm().flows().getRequiredAction(WebAuthnRegisterFactory.PROVIDER_ID);
        rapr.setEnabled(false);
        testRealm().flows().updateRequiredAction(WebAuthnRegisterFactory.PROVIDER_ID, rapr);
        try {
            provideUsernamePassword("test-user@localhost");

            // Assert that the login evaluates to an error, as all required elements to not validate to successful
            errorPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
            testRealm().flows().removeRequiredAction(WebAuthnRegisterFactory.PROVIDER_ID);
        }
    }

    /**
     * This test checks that if a REQUIRED authentication execution which has isUserSetupAllowed -> true
     * has its requiredActionProvider enabled, than it will login and show the otpSetup page.
     * NOTE: webauthn currently isn't configured by default in the realm. When this changes, this test will need to be adapted
     */
    @Test
    public void testLoginWithWithNoWebAuthnCredential(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithRequiredWebAuthn(newFlowAlias);

        RequiredActionProviderSimpleRepresentation requiredActionRepresentation = new RequiredActionProviderSimpleRepresentation();
        requiredActionRepresentation.setName("WebAuthn Required Action");
        requiredActionRepresentation.setProviderId(WebAuthnRegisterFactory.PROVIDER_ID);
        testRealm().flows().registerRequiredAction(requiredActionRepresentation);

        try {
            provideUsernamePassword("test-user@localhost");

            // Assert that in this case you arrive to an webauthn setup
            Assert.assertTrue(driver.getCurrentUrl().contains("required-action?execution=" + WebAuthnRegisterFactory.PROVIDER_ID));

        } finally {
            revertFlows("browser - copy 1");
            testRealm().flows().removeRequiredAction(WebAuthnRegisterFactory.PROVIDER_ID);
            UserRepresentation user = testRealm().users().search("test-user@localhost").get(0);
            user.setRequiredActions(Collections.emptyList());
            testRealm().users().get(user.getId()).update(user);;
        }
    }

    /**
     * This flow contains:
     * UsernamePasswordForm REQUIRED
     * WebAuthn REQUIRED
     *
     * @param newFlowAlias
     */
    private void configureBrowserFlowWithRequiredWebAuthn(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(Requirement.REQUIRED, UsernamePasswordFormFactory.PROVIDER_ID)
                        .addAuthenticatorExecution(Requirement.REQUIRED, WebAuthnAuthenticatorFactory.PROVIDER_ID)
                ).defineAsBrowserFlow() // Activate this new flow
        );
    }


    /**
     * This test checks that if a alternative authentication execution which has no credential, and the alternative is a flow,
     * then the selection mechanism will see that there's no viable alternative, and move on to the next execution (in this case the flow)
     */
    @Test
    public void testLoginWithWithNoOTPCredentialAndAlternativeActionProvider(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithAlternativeOTPAndPassword(newFlowAlias);
        try {
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.assertCurrent();
            loginUsernameOnlyPage.login("test-user@localhost");

            // Assert that the login skipped the OTP authenticator and moved to the password
            passwordPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }

    /**
     * This flow contains:
     * UsernameForm REQUIRED
     * Subflow REQUIRED
     * ** OTPForm ALTERNATIVE
     * ** sub-subflow ALTERNATIVE
     * **** PasswordForm ALTERNATIVE
     *
     * The passwordform is in a sub-subflow, because otherwise credential preference mechanisms would take over and any
     * way go into the password form
     *
     * @param newFlowAlias
     */
    private void configureBrowserFlowWithAlternativeOTPAndPassword(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.REQUIRED, subflow -> subflow
                                .addAuthenticatorExecution(Requirement.ALTERNATIVE, OTPFormAuthenticatorFactory.PROVIDER_ID)
                                .addSubFlowExecution(Requirement.ALTERNATIVE, sf -> sf
                                        .addAuthenticatorExecution(Requirement.ALTERNATIVE, PasswordFormFactory.PROVIDER_ID))
                                )
                ).defineAsBrowserFlow() // Activate this new flow
        );
    }


    /**
     * This test checks that if a alternative authentication execution which has isUserSetupAllowed -> true for
     * but is not a CredentialValidator (and therefore will not be removed by the selection mechanism),
     * then it will not try to create the required action, and will instead move to the next alternative
     */
    @Test
    public void testLoginWithWithNoWebAuthnCredentialAndAlternativeActionProvider(){
        String newFlowAlias = "browser - copy 1";
        configureBrowserFlowWithAlternativeWebAuthnAndPassword(newFlowAlias);
        try {
            loginUsernameOnlyPage.open();
            loginUsernameOnlyPage.assertCurrent();
            loginUsernameOnlyPage.login("test-user@localhost");

            // Assert that the login skipped the OTP authenticator and moved to the password
            passwordPage.assertCurrent();

        } finally {
            revertFlows("browser - copy 1");
        }
    }

    /**
     * This flow contains:
     * UsernameForm REQUIRED
     * Subflow REQUIRED
     * ** WebAuthn ALTERNATIVE
     * ** sub-subflow ALTERNATIVE
     * **** PasswordForm ALTERNATIVE
     *
     * The password form is in a sub-subflow, because otherwise credential preference mechanisms would take over and any
     * way go into the password form. Note that this flow only works for the test because WebAuthn is a isUserSetupAllowed
     * flow that is not a CredentialValidator. When this changes, this flow will have to be modified to use another appropriate
     * authenticator.
     *
     * @param newFlowAlias
     */
    private void configureBrowserFlowWithAlternativeWebAuthnAndPassword(String newFlowAlias) {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow(newFlowAlias));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow(newFlowAlias)
                .inForms(forms -> forms
                        .clear()
                        .addAuthenticatorExecution(Requirement.REQUIRED, UsernameFormFactory.PROVIDER_ID)
                        .addSubFlowExecution(Requirement.REQUIRED, subflow -> subflow
                                .addAuthenticatorExecution(Requirement.ALTERNATIVE, WebAuthnAuthenticatorFactory.PROVIDER_ID)
                                .addSubFlowExecution(Requirement.ALTERNATIVE, sf -> sf
                                        .addAuthenticatorExecution(Requirement.ALTERNATIVE, PasswordFormFactory.PROVIDER_ID))
                        )
                ).defineAsBrowserFlow() // Activate this new flow
        );
    }


    private void revertFlows(String flowToDeleteAlias) {
        List<AuthenticationFlowRepresentation> flows = testRealm().flows().getFlows();

        // Set default browser flow
        RealmRepresentation realm = testRealm().toRepresentation();
        realm.setBrowserFlow(DefaultAuthenticationFlows.BROWSER_FLOW);
        testRealm().update(realm);

        AuthenticationFlowRepresentation flowRepresentation = AbstractAuthenticationTest.findFlowByAlias(flowToDeleteAlias, flows);

        // Throw error if flow doesn't exists to ensure we did not accidentally use different alias of non-existing flow when
        // calling this method
        if (flowRepresentation == null) {
            throw new IllegalArgumentException("The flow with alias " + flowToDeleteAlias + " did not exists");
        }

        testRealm().flows().deleteFlow(flowRepresentation.getId());
    }
}
