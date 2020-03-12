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
package org.keycloak.testsuite;

import io.appium.java_client.AppiumDriver;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.RealmsResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.common.Profile;
import org.keycloak.common.util.KeycloakUriBuilder;
import org.keycloak.common.util.Time;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.resources.account.AccountFormService;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.arquillian.AuthServerTestEnricher;
import org.keycloak.testsuite.arquillian.KcArquillian;
import org.keycloak.testsuite.arquillian.SuiteContext;
import org.keycloak.testsuite.arquillian.TestContext;
import org.keycloak.testsuite.auth.page.AuthRealm;
import org.keycloak.testsuite.auth.page.AuthServer;
import org.keycloak.testsuite.auth.page.AuthServerContextRoot;
import org.keycloak.testsuite.auth.page.WelcomePage;
import org.keycloak.testsuite.auth.page.account.Account;
import org.keycloak.testsuite.auth.page.login.OIDCLogin;
import org.keycloak.testsuite.auth.page.login.UpdatePassword;
import org.keycloak.testsuite.client.KeycloakTestingClient;
import org.keycloak.testsuite.pages.LoginPasswordUpdatePage;
import org.keycloak.testsuite.util.AdminClientUtil;
import org.keycloak.testsuite.util.DroneUtils;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.TestCleanup;
import org.keycloak.testsuite.util.TestEventsLogger;
import org.openqa.selenium.WebDriver;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.keycloak.testsuite.admin.Users.setPasswordFor;
import static org.keycloak.testsuite.auth.page.AuthRealm.ADMIN;
import static org.keycloak.testsuite.auth.page.AuthRealm.MASTER;
import static org.keycloak.testsuite.util.URLUtils.navigateToUri;

/**
 *
 * @author tkyjovsk
 */
@RunWith(KcArquillian.class)
@RunAsClient
public abstract class AbstractKeycloakTest {

    protected static final boolean AUTH_SERVER_SSL_REQUIRED = Boolean.parseBoolean(System.getProperty("auth.server.ssl.required", "false"));
    protected static final String AUTH_SERVER_SCHEME = AUTH_SERVER_SSL_REQUIRED ? "https" : "http";
    protected static final String AUTH_SERVER_PORT = AUTH_SERVER_SSL_REQUIRED ? System.getProperty("auth.server.https.port", "8543") : System.getProperty("auth.server.http.port", "8180");

    protected static final String ENGLISH_LOCALE_NAME = "English";

    protected Logger log = Logger.getLogger(this.getClass());

    @ArquillianResource
    protected SuiteContext suiteContext;

    @ArquillianResource
    protected TestContext testContext;

    protected Keycloak adminClient;

    protected KeycloakTestingClient testingClient;

    @ArquillianResource
    protected OAuthClient oauth;

    protected List<RealmRepresentation> testRealmReps;

    @Drone
    protected WebDriver driver;

    @Page
    protected AuthServerContextRoot authServerContextRootPage;
    @Page
    protected AuthServer authServerPage;

    @Page
    protected AuthRealm masterRealmPage;

    @Page
    protected Account accountPage;

    @Page
    protected OIDCLogin loginPage;

    @Page
    protected UpdatePassword updatePasswordPage;

    @Page
    protected LoginPasswordUpdatePage passwordUpdatePage;

    @Page
    protected WelcomePage welcomePage;

    private PropertiesConfiguration constantsProperties;

    private boolean resetTimeOffset;

    @Before
    public void beforeAbstractKeycloakTest() throws Exception {
        adminClient = testContext.getAdminClient();
        if (adminClient == null || adminClient.isClosed()) {
            reconnectAdminClient();
        }

        getTestingClient();

        setDefaultPageUriParameters();

        TestEventsLogger.setDriver(driver);

        // The backend cluster nodes may not be yet started. Password will be updated later for cluster setup.
        if (!AuthServerTestEnricher.AUTH_SERVER_CLUSTER) {
            updateMasterAdminPassword();
        }

        beforeAbstractKeycloakTestRealmImport();

        if (testContext.getTestRealmReps().isEmpty()) {
            importTestRealms();

            if (!isImportAfterEachMethod()) {
                testContext.setTestRealmReps(testRealmReps);
            }

            afterAbstractKeycloakTestRealmImport();
        }

        oauth.init(driver);

    }

    public void reconnectAdminClient() throws Exception {
        testContext.reconnectAdminClient();
        adminClient = testContext.getAdminClient();
    }

    protected void beforeAbstractKeycloakTestRealmImport() throws Exception {
    }
    protected void postAfterAbstractKeycloak() {
    }

    protected void afterAbstractKeycloakTestRealmImport() {}

    @After
    public void afterAbstractKeycloakTest() {
        if (resetTimeOffset) {
            resetTimeOffset();
        }

        if (isImportAfterEachMethod()) {
            log.info("removing test realms after test method");
            for (RealmRepresentation testRealm : testRealmReps) {
                removeRealm(testRealm.getRealm());
            }
        } else {
            log.info("calling all TestCleanup");
            // Remove all sessions
            testContext.getTestRealmReps().stream().forEach((r)->testingClient.testing().removeUserSessions(r.getRealm()));

            // Cleanup objects
            for (TestCleanup cleanup : testContext.getCleanups().values()) {
                try {
                    if (cleanup != null) cleanup.executeCleanup();
                } catch (Exception e) {
                    log.error("failed cleanup!", e);
                    throw new RuntimeException(e);
                }
            }
            testContext.getCleanups().clear();
        }

        postAfterAbstractKeycloak();

        // Remove all browsers from queue
        DroneUtils.resetQueue();
    }

    protected TestCleanup getCleanup(String realmName) {
        return testContext.getOrCreateCleanup(realmName);
    }

    protected TestCleanup getCleanup() {
        return getCleanup("test");
    }

    protected boolean isImportAfterEachMethod() {
        return false;
    }

    protected void updateMasterAdminPassword() {
        if (!suiteContext.isAdminPasswordUpdated()) {
            log.debug("updating admin password");

            welcomePage.navigateTo();
            if (!welcomePage.isPasswordSet()) {
                welcomePage.setPassword("admin", "admin");
            }

            suiteContext.setAdminPasswordUpdated(true);
        }
    }

    public void deleteAllCookiesForMasterRealm() {
        deleteAllCookiesForRealm(accountPage);
    }

    protected void deleteAllCookiesForRealm(Account realmAccountPage) {
        // masterRealmPage.navigateTo();
        realmAccountPage.navigateTo(); // Because IE webdriver freezes when loading a JSON page (realm page), we need to use this alternative
        log.info("deleting cookies in '" + realmAccountPage.getAuthRealm() + "' realm");
        driver.manage().deleteAllCookies();
    }

    protected void deleteAllCookiesForRealm(String realmName) {
        // masterRealmPage.navigateTo();
        navigateToUri(accountPage.getAuthRoot() + "/realms/" + realmName + "/account"); // Because IE webdriver freezes when loading a JSON page (realm page), we need to use this alternative
        log.info("deleting cookies in '" + realmName + "' realm");
        driver.manage().deleteAllCookies();
    }

    // this is useful mainly for smartphones as cookies deletion doesn't work there
    protected void deleteAllSessionsInRealm(String realmName) {
        log.info("removing all sessions from '" + realmName + "' realm...");
        try {
            adminClient.realm(realmName).logoutAll();
            log.info("sessions successfully deleted");
        }
        catch (NotFoundException e) {
            log.warn("realm not found");
        }
    }

    protected void resetRealmSession(String realmName) {
        deleteAllCookiesForRealm(realmName);

        if (driver instanceof AppiumDriver) { // smartphone drivers don't support cookies deletion
            try {
                log.info("resetting realm session");

                final RealmRepresentation realmRep = adminClient.realm(realmName).toRepresentation();

                deleteAllSessionsInRealm(realmName); // logout users

                if (realmRep.isInternationalizationEnabled()) { // reset the locale
                    String locale = getDefaultLocaleName(realmRep.getRealm());
                    loginPage.localeDropdown().selectByText(locale);
                    log.info("locale reset to " + locale);
                }
            } catch (NotFoundException e) {
                log.warn("realm not found");
            }
        }
    }

    protected String getDefaultLocaleName(String realmName) {
        return ENGLISH_LOCALE_NAME;
    }

    public void setDefaultPageUriParameters() {
        masterRealmPage.setAuthRealm(MASTER);
        loginPage.setAuthRealm(MASTER);
    }

    public KeycloakTestingClient getTestingClient() {
        if (testingClient == null) {
            testingClient = testContext.getTestingClient();
        }
        return testingClient;
    }

    public TestContext getTestContext() {
        return testContext;
    }

    public Keycloak getAdminClient() {
        return adminClient;
    }

    public abstract void addTestRealms(List<RealmRepresentation> testRealms);

    private void addTestRealms() {
        log.debug("loading test realms");
        if (testRealmReps == null) {
            testRealmReps = new ArrayList<>();
        }
        if (testRealmReps.isEmpty()) {
            addTestRealms(testRealmReps);
        }
    }

    public void importTestRealms() {
        addTestRealms();
        log.info("importing test realms");
        for (RealmRepresentation testRealm : testRealmReps) {
            if (modifyRealmForSSL()) {
                if (AUTH_SERVER_SSL_REQUIRED) {
                    log.debugf("Modifying %s for SSL", testRealm.getId());
                    for (ClientRepresentation cr : testRealm.getClients()) {
                        modifyMainUrls(cr);
                        modifyRedirectUrls(cr);
                        modifySamlAttributes(cr);
                    }
                }
            }
            importRealm(testRealm);
        }
    }

    private void modifySamlAttributes(ClientRepresentation cr) {
        if (cr.getProtocol() != null && cr.getProtocol().equals("saml")) {
            log.info("Modifying attributes of SAML client: " + cr.getClientId());
            for (Map.Entry<String, String> entry : cr.getAttributes().entrySet()) {
                cr.getAttributes().put(entry.getKey(), replaceHttpValuesWithHttps(entry.getValue()));
            }
        }
    }

    private void modifyRedirectUrls(ClientRepresentation cr) {
        if (cr.getRedirectUris() != null && cr.getRedirectUris().size() > 0) {
            List<String> redirectUrls = cr.getRedirectUris();
            List<String> fixedRedirectUrls = new ArrayList<>(redirectUrls.size());
            for (String url : redirectUrls) {
                fixedRedirectUrls.add(replaceHttpValuesWithHttps(url));
            }
            cr.setRedirectUris(fixedRedirectUrls);
        }
    }

    private void modifyMainUrls(ClientRepresentation cr) {
        cr.setBaseUrl(replaceHttpValuesWithHttps(cr.getBaseUrl()));
        cr.setAdminUrl(replaceHttpValuesWithHttps(cr.getAdminUrl()));
    }

    private String replaceHttpValuesWithHttps(String input) {
        if (input == null) {
            return null;
        }
        if ("".equals(input)) {
            return "";
        }
        return input
              .replace("http", "https")
              .replace("8080", "8543")
              .replace("8180", "8543");
    }

    /**
     * @return Return <code>true</code> if you wish to automatically post-process realm and replace
     * all http values with https (and correct ports).
     */
    protected boolean modifyRealmForSSL() {
        return false;
    }


    protected void removeAllRealmsDespiteMaster() {
        // remove all realms (accidentally left by other tests) except for master
        adminClient.realms().findAll().stream()
                .map(RealmRepresentation::getRealm)
                .filter(realmName -> ! realmName.equals("master"))
                .forEach(this::removeRealm);
        assertThat(adminClient.realms().findAll().size(), is(equalTo(1)));
    }


    public void importRealm(RealmRepresentation realm) {
        log.debug("--importing realm: " + realm.getRealm());
        try {
            adminClient.realms().realm(realm.getRealm()).remove();
            log.debug("realm already existed on server, re-importing");
        } catch (NotFoundException ignore) {
            // expected when realm does not exist
        }
        adminClient.realms().create(realm);
    }

    public void removeRealm(String realmName) {
        log.info("removing realm: " + realmName);
        try {
            adminClient.realms().realm(realmName).remove();
        } catch (NotFoundException e) {
        }
    }

    public RealmsResource realmsResouce() {
        return adminClient.realms();
    }

    /**
     * Creates a user in the given realm and returns its ID.
     *
     * @param realm           Realm name
     * @param username        Username
     * @param password        Password
     * @param requiredActions
     * @return ID of the newly created user
     */
    public String createUser(String realm, String username, String password, String... requiredActions) {
        UserRepresentation homer = createUserRepresentation(username, password);
        homer.setRequiredActions(Arrays.asList(requiredActions));

        return ApiUtil.createUserWithAdminClient(adminClient.realm(realm), homer);
    }

    public String createUser(String realm, String username, String password, String firstName, String lastName, String email) {
        UserRepresentation homer = createUserRepresentation(username, email, firstName, lastName, true, password);
        return ApiUtil.createUserWithAdminClient(adminClient.realm(realm), homer);
    }

    public static UserRepresentation createUserRepresentation(String username, String email, String firstName, String lastName, boolean enabled) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(enabled);
        return user;
    }

    public static UserRepresentation createUserRepresentation(String username, String email, String firstName, String lastName, boolean enabled, String password) {
        UserRepresentation user = createUserRepresentation(username, email, firstName, lastName, enabled);
        setPasswordFor(user, password);
        return user;
    }

    public static UserRepresentation createUserRepresentation(String username, String password) {
        UserRepresentation user = createUserRepresentation(username, null, null, null, true, password);
        return user;
    }

    public void setRequiredActionEnabled(String realm, String requiredAction, boolean enabled, boolean defaultAction) {
        AuthenticationManagementResource managementResource = adminClient.realm(realm).flows();

        RequiredActionProviderRepresentation action = managementResource.getRequiredAction(requiredAction);
        action.setEnabled(enabled);
        action.setDefaultAction(defaultAction);

        managementResource.updateRequiredAction(requiredAction, action);
    }

    public void setRequiredActionEnabled(String realm, String userId, String requiredAction, boolean enabled) {
        UsersResource usersResource = adminClient.realm(realm).users();

        UserResource userResource = usersResource.get(userId);
        UserRepresentation userRepresentation = userResource.toRepresentation();

        List<String> requiredActions = userRepresentation.getRequiredActions();
        if (enabled && !requiredActions.contains(requiredAction)) {
            requiredActions.add(requiredAction);
        } else if (!enabled && requiredActions.contains(requiredAction)) {
            requiredActions.remove(requiredAction);
        }

        userResource.update(userRepresentation);
    }

    /**
     * Sets time of day by calculating time offset and using setTimeOffset() to set it.
     *
     * @param hour hour of day
     * @param minute minute
     * @param second second
     */
    public void setTimeOfDay(int hour, int minute, int second) {
        setTimeOfDay(hour, minute, second, 0);
    }

    /**
     * Sets time of day by calculating time offset and using setTimeOffset() to set it.
     *
     * @param hour hour of day
     * @param minute minute
     * @param second second
     * @param addSeconds additional seconds to add to offset time
     */
    public void setTimeOfDay(int hour, int minute, int second, int addSeconds) {
        Calendar now = Calendar.getInstance();
        now.set(Calendar.HOUR_OF_DAY, hour);
        now.set(Calendar.MINUTE, minute);
        now.set(Calendar.SECOND, second);
        int offset = (int) ((now.getTime().getTime() - System.currentTimeMillis()) / 1000);

        setTimeOffset(offset + addSeconds);
    }

    /**
     * Sets time offset in seconds that will be added to Time.currentTime() and Time.currentTimeMillis() both for client and server.
     *
     * @param offset
     */
    public void setTimeOffset(int offset) {
        String response = invokeTimeOffset(offset);
        resetTimeOffset = offset != 0;
        log.debugv("Set time offset, response {0}", response);
    }

    public void resetTimeOffset() {
        String response = invokeTimeOffset(0);
        resetTimeOffset = false;
        log.debugv("Reset time offset, response {0}", response);
    }

    public int getCurrentTime() {
        return Time.currentTime();
    }

    protected String invokeTimeOffset(int offset) {
        // adminClient depends on Time.offset for auto-refreshing tokens
        Time.setOffset(offset);
        Map result = testingClient.testing().setTimeOffset(Collections.singletonMap("offset", String.valueOf(offset)));
        return String.valueOf(result);
    }

    private void loadConstantsProperties() throws ConfigurationException {
        constantsProperties = new PropertiesConfiguration(System.getProperty("testsuite.constants"));
        constantsProperties.setThrowExceptionOnMissing(true);
    }

    protected PropertiesConfiguration getConstantsProperties() throws ConfigurationException {
        if (constantsProperties == null) {
            loadConstantsProperties();
        }
        return constantsProperties;
    }

    public URI getAuthServerRoot() {
        try {
            return KeycloakUriBuilder.fromUri(suiteContext.getAuthServerInfo().getContextRoot().toURI()).path("/auth/").build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public Logger getLogger() {
        return log;
    }

    protected String getAccountRedirectUrl(String realm) {
        return AccountFormService
              .loginRedirectUrl(UriBuilder.fromUri(oauth.AUTH_SERVER_ROOT))
              .build(realm)
              .toString();
    }

    protected String getAccountRedirectUrl() {
        return getAccountRedirectUrl("test");
    }

    protected static InputStream httpsAwareConfigurationStream(InputStream input) throws IOException {
        if (!AUTH_SERVER_SSL_REQUIRED) {
            return input;
        }
        PipedInputStream in = new PipedInputStream();
        final PipedOutputStream out = new PipedOutputStream(in);
        try (PrintWriter pw = new PrintWriter(out)) {
            try (Scanner s = new Scanner(input)) {
                while (s.hasNextLine()) {
                    String lineWithReplaces = s.nextLine().replace("http://localhost:8180/auth", AUTH_SERVER_SCHEME + "://localhost:" + AUTH_SERVER_PORT + "/auth");
                    pw.println(lineWithReplaces);
                }
            }
        }
        return in;
    }
}
