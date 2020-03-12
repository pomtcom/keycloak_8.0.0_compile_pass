package org.keycloak.testsuite.broker;

import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.arquillian.SuiteContext;

import java.util.List;
import java.util.Map;

import static java.util.Locale.ENGLISH;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.keycloak.OAuth2Constants.UI_LOCALES_PARAM;
import static org.keycloak.testsuite.broker.BrokerTestConstants.IDP_OIDC_ALIAS;
import static org.keycloak.testsuite.broker.BrokerTestConstants.IDP_OIDC_PROVIDER_ID;
import static org.keycloak.testsuite.broker.BrokerTestTools.createIdentityProvider;
import static org.keycloak.testsuite.broker.BrokerTestTools.waitForPage;

public class KcOidcBrokerUiLocalesDisabledTest extends AbstractBrokerTest {

    @Override
    protected BrokerConfiguration getBrokerConfiguration() {
        return new KcOidcBrokerConfigurationWithUiLocalesDisabled();
    }

    private class KcOidcBrokerConfigurationWithUiLocalesDisabled extends KcOidcBrokerConfiguration {

        @Override
        public IdentityProviderRepresentation setUpIdentityProvider(SuiteContext suiteContext) {
            IdentityProviderRepresentation idp = createIdentityProvider(IDP_OIDC_ALIAS, IDP_OIDC_PROVIDER_ID);
            Map<String, String> config = idp.getConfig();
            applyDefaultConfiguration(suiteContext, config);
            config.put("uiLocales", "false");
            return idp;
        }
    }

    @Override
    protected void loginUser() {
        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        driver.navigate().to(driver.getCurrentUrl());


        log.debug("Clicking social " + bc.getIDPAlias());
        loginPage.clickSocial(bc.getIDPAlias());

        waitForPage(driver, "log in to", true);

        Assert.assertThat("Driver should be on the provider realm page right now",
                driver.getCurrentUrl(), containsString("/auth/realms/" + bc.providerRealmName() + "/"));

        Assert.assertThat(UI_LOCALES_PARAM + "=" + ENGLISH.toLanguageTag() + " should be part of the url",
                driver.getCurrentUrl(), not(containsString(UI_LOCALES_PARAM + "=" + ENGLISH.toLanguageTag())));

        loginPage.login(bc.getUserLogin(), bc.getUserPassword());
        waitForPage(driver, "update account information", false);

        updateAccountInformationPage.assertCurrent();

        Assert.assertThat("We must be on correct realm right now",
                driver.getCurrentUrl(), containsString("/auth/realms/" + bc.consumerRealmName() + "/"));

        log.debug("Updating info on updateAccount page");
        updateAccountInformationPage.updateAccountInformation(bc.getUserLogin(), bc.getUserEmail(), "Firstname", "Lastname");

        UsersResource consumerUsers = adminClient.realm(bc.consumerRealmName()).users();

        int userCount = consumerUsers.count();
        Assert.assertTrue("There must be at least one user", userCount > 0);

        List<UserRepresentation> users = consumerUsers.search("", 0, userCount);

        boolean isUserFound = false;
        for (UserRepresentation user : users) {
            if (user.getUsername().equals(bc.getUserLogin()) && user.getEmail().equals(bc.getUserEmail())) {
                isUserFound = true;
                break;
            }
        }

        Assert.assertTrue("There must be user " + bc.getUserLogin() + " in realm " + bc.consumerRealmName(),
                isUserFound);
    }
}
