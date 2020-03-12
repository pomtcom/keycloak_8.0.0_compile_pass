/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.credential;

import org.keycloak.common.util.Base64;

import com.webauthn4j.data.WebAuthnAuthenticationContext;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.statement.AttestationStatement;
import org.keycloak.models.credential.WebAuthnCredentialModel;

public class WebAuthnCredentialModelInput implements CredentialInput {

    public static final String WEBAUTHN_CREDENTIAL_TYPE = WebAuthnCredentialModel.TYPE;

    private AttestedCredentialData attestedCredentialData;
    private AttestationStatement attestationStatement;
    private WebAuthnAuthenticationContext authenticationContext;
    private long count;
    private String credentialDBId;

    @Override
    public String getCredentialId() {
        return credentialDBId;
    }

    @Override
    public String getChallengeResponse() {
        throw new UnsupportedOperationException("WebAuthn credential doesn't support getChallengeResponse");
    }

    @Override
    public String getType() {
        return WEBAUTHN_CREDENTIAL_TYPE;
    }

    public WebAuthnCredentialModelInput() {

    }

    public AttestedCredentialData getAttestedCredentialData() {
        return attestedCredentialData;
    }

    public AttestationStatement getAttestationStatement() {
        return attestationStatement;
    }

    public long getCount() {
        return count;
    }

    public WebAuthnAuthenticationContext getAuthenticationContext() {
        return authenticationContext;
    }

    public void setAuthenticationContext(WebAuthnAuthenticationContext authenticationContext) {
        this.authenticationContext = authenticationContext;
    }

    public void setAttestedCredentialData(AttestedCredentialData attestedCredentialData) {
        this.attestedCredentialData = attestedCredentialData;
    }

    public void setAttestationStatement(AttestationStatement attestationStatement) {
        this.attestationStatement = attestationStatement;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public String getCredentialDBId() {
        return credentialDBId;
    }

    public void setCredentialDBId(String credentialDBId) {
        this.credentialDBId = credentialDBId;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (credentialDBId != null)
            sb.append("Credential DB Id = ")
              .append(credentialDBId)
              .append(",");
        if (attestationStatement != null)
            sb.append("Attestation Statement Format = ")
              .append(attestationStatement.getFormat())
              .append(",");
        if (attestedCredentialData != null) {
            sb.append("AAGUID = ")
              .append(attestedCredentialData.getAaguid().toString())
              .append(",");
            sb.append("CREDENTIAL_ID = ")
              .append(Base64.encodeBytes(attestedCredentialData.getCredentialId()))
              .append(",");
            COSEKey credPubKey = attestedCredentialData.getCOSEKey();
            byte[] keyId = credPubKey.getKeyId();
            if (keyId != null)
                sb.append("CREDENTIAL_PUBLIC_KEY.key_id = ")
                  .append(Base64.encodeBytes(keyId))
                  .append(",");
            sb.append("CREDENTIAL_PUBLIC_KEY.algorithm = ")
              .append(String.valueOf(credPubKey.getAlgorithm().getValue()))
              .append(",");
            sb.append("CREDENTIAL_PUBLIC_KEY.key_type = ")
              .append(credPubKey.getKeyType().name())
              .append(",");
        }
        if (authenticationContext != null) {
            // only set on Authentication
            sb.append("Credential Id = ")
              .append(Base64.encodeBytes(authenticationContext.getCredentialId()))
              .append(",");
        }
        if (sb.length() > 0)
            sb.deleteCharAt(sb.lastIndexOf(","));
        return sb.toString();
    }
}
