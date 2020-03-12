package org.keycloak.testsuite.url;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.arquillian.AuthServerTestEnricher;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

public abstract class AbstractHostnameTest extends AbstractKeycloakTest {

    private static final Logger LOGGER = Logger.getLogger(AbstractHostnameTest.class);

    @ArquillianResource
    protected ContainerController controller;

    void reset() throws Exception {
        LOGGER.info("Reset hostname config to default");

        if (suiteContext.getAuthServerInfo().isUndertow()) {
            controller.stop(suiteContext.getAuthServerInfo().getQualifier());
            removeProperties("keycloak.hostname.provider",
                    "keycloak.frontendUrl",
                    "keycloak.adminUrl",
                    "keycloak.hostname.default.forceBackendUrlToFrontendUrl",
                    "keycloak.hostname.fixed.hostname",
                    "keycloak.hostname.fixed.httpPort",
                    "keycloak.hostname.fixed.httpsPort",
                    "keycloak.hostname.fixed.alwaysHttps");
            controller.start(suiteContext.getAuthServerInfo().getQualifier());
        } else if (suiteContext.getAuthServerInfo().isJBossBased()) {
            executeCli("/subsystem=keycloak-server/spi=hostname:remove",
                    "/subsystem=keycloak-server/spi=hostname/:add(default-provider=default)",
                    "/subsystem=keycloak-server/spi=hostname/provider=default/:add(properties={frontendUrl => \"${keycloak.frontendUrl:}\",forceBackendUrlToFrontendUrl => \"false\"},enabled=true)");
        } else {
            throw new RuntimeException("Don't know how to config");
        }

        reconnectAdminClient();
    }

    void configureDefault(String frontendUrl, boolean forceBackendUrlToFrontendUrl, String adminUrl) throws Exception {
        LOGGER.infov("Configuring default hostname provider: frontendUrl={0}, forceBackendUrlToFrontendUrl={1}, adminUrl={3}", frontendUrl, forceBackendUrlToFrontendUrl, adminUrl);

        if (suiteContext.getAuthServerInfo().isUndertow()) {
            controller.stop(suiteContext.getAuthServerInfo().getQualifier());
            System.setProperty("keycloak.hostname.provider", "default");
            System.setProperty("keycloak.frontendUrl", frontendUrl);
            if (adminUrl != null){
                System.setProperty("keycloak.adminUrl", adminUrl);
            }
            System.setProperty("keycloak.hostname.default.forceBackendUrlToFrontendUrl", String.valueOf(forceBackendUrlToFrontendUrl));
            controller.start(suiteContext.getAuthServerInfo().getQualifier());
        } else if (suiteContext.getAuthServerInfo().isJBossBased()) {
            executeCli("/subsystem=keycloak-server/spi=hostname:remove",
                    "/subsystem=keycloak-server/spi=hostname/:add(default-provider=default)",
                    "/subsystem=keycloak-server/spi=hostname/provider=default/:add(properties={" +
                            "frontendUrl => \"" + frontendUrl + "\"" +
                            ",forceBackendUrlToFrontendUrl => \"" + forceBackendUrlToFrontendUrl + "\"" +
                            (adminUrl != null ? ",adminUrl=\"" + adminUrl + "\"" : "") + "},enabled=true)");
        } else {
            throw new RuntimeException("Don't know how to config");
        }

        reconnectAdminClient();
    }

    void configureFixed(String hostname, int httpPort, int httpsPort, boolean alwaysHttps) throws Exception {


        if (suiteContext.getAuthServerInfo().isUndertow()) {
            controller.stop(suiteContext.getAuthServerInfo().getQualifier());
            System.setProperty("keycloak.hostname.provider", "fixed");
            System.setProperty("keycloak.hostname.fixed.hostname", hostname);
            System.setProperty("keycloak.hostname.fixed.httpPort", String.valueOf(httpPort));
            System.setProperty("keycloak.hostname.fixed.httpsPort", String.valueOf(httpsPort));
            System.setProperty("keycloak.hostname.fixed.alwaysHttps", String.valueOf(alwaysHttps));
            controller.start(suiteContext.getAuthServerInfo().getQualifier());
        } else if (suiteContext.getAuthServerInfo().isJBossBased()) {
            executeCli("/subsystem=keycloak-server/spi=hostname:remove",
                    "/subsystem=keycloak-server/spi=hostname/:add(default-provider=fixed)",
                    "/subsystem=keycloak-server/spi=hostname/provider=fixed/:add(properties={hostname => \"" + hostname + "\",httpPort => \"" + httpPort + "\",httpsPort => \"" + httpsPort + "\",alwaysHttps => \"" + alwaysHttps + "\"},enabled=true)");

        } else {
            throw new RuntimeException("Don't know how to config");
        }

        reconnectAdminClient();
    }

    private void executeCli(String... commands) throws Exception {
        OnlineManagementClient client = AuthServerTestEnricher.getManagementClient();
        Administration administration = new Administration(client);

        LOGGER.debug("Running CLI commands:");
        for (String c : commands) {
            LOGGER.debug(c);
            client.execute(c).assertSuccess();
        }
        LOGGER.debug("Done");

        administration.reload();

        client.close();
    }

    private void removeProperties(String... keys) {
        for (String k : keys) {
            System.getProperties().remove(k);
        }
    }


}
