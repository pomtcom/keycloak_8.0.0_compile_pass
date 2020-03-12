package org.keycloak.testsuite.authz;

import org.keycloak.common.Profile;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.representations.AccessToken;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.EnableFeature;

/**
 * @author mhajas
 */
@EnableFeature(value = Profile.Feature.UPLOAD_SCRIPTS, skipRestart = true)
public abstract class AbstractAuthzTest extends AbstractKeycloakTest {

    protected AccessToken toAccessToken(String rpt) {
        AccessToken accessToken;

        try {
            accessToken = new JWSInput(rpt).readJsonContent(AccessToken.class);
        } catch (JWSInputException cause) {
            throw new RuntimeException("Failed to deserialize RPT", cause);
        }
        return accessToken;
    }
}
