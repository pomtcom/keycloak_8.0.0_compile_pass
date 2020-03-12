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
package org.keycloak.protocol.oidc;

public final class OIDCConfigAttributes {

    public static final String USER_INFO_RESPONSE_SIGNATURE_ALG = "user.info.response.signature.alg";

    public static final String REQUEST_OBJECT_SIGNATURE_ALG = "request.object.signature.alg";

    public static final String REQUEST_OBJECT_REQUIRED = "request.object.required";
    public static final String REQUEST_OBJECT_REQUIRED_REQUEST_OR_REQUEST_URI = "request or request_uri";
    public static final String REQUEST_OBJECT_REQUIRED_REQUEST = "request only";
    public static final String REQUEST_OBJECT_REQUIRED_REQUEST_URI = "request_uri only";

    public static final String JWKS_URL = "jwks.url";

    public static final String USE_JWKS_URL = "use.jwks.url";

    public static final String EXCLUDE_SESSION_STATE_FROM_AUTH_RESPONSE = "exclude.session.state.from.auth.response";

    public static final String USE_MTLS_HOK_TOKEN = "tls.client.certificate.bound.access.tokens";

    public static final String ID_TOKEN_SIGNED_RESPONSE_ALG = "id.token.signed.response.alg";

    public static final String ID_TOKEN_ENCRYPTED_RESPONSE_ALG = "id.token.encrypted.response.alg";

    public static final String ID_TOKEN_ENCRYPTED_RESPONSE_ENC = "id.token.encrypted.response.enc";

    public static final String ACCESS_TOKEN_SIGNED_RESPONSE_ALG = "access.token.signed.response.alg";

    public static final String ACCESS_TOKEN_LIFESPAN = "access.token.lifespan";

    public static final String PKCE_CODE_CHALLENGE_METHOD = "pkce.code.challenge.method";

    private OIDCConfigAttributes() {
    }

}
