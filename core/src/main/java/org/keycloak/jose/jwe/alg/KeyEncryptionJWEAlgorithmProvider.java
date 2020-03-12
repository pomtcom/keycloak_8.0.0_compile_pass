/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.jose.jwe.alg;

import java.security.Key;

import javax.crypto.Cipher;

import org.keycloak.jose.jwe.JWEKeyStorage;
import org.keycloak.jose.jwe.enc.JWEEncryptionProvider;

public abstract class KeyEncryptionJWEAlgorithmProvider implements JWEAlgorithmProvider {

    @Override
    public byte[] decodeCek(byte[] encodedCek, Key privateKey) throws Exception {
        Cipher cipher = getCipherProvider();
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encodedCek);
    }

    @Override
    public byte[] encodeCek(JWEEncryptionProvider encryptionProvider, JWEKeyStorage keyStorage, Key publicKey) throws Exception {
        Cipher cipher = getCipherProvider();
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] cekBytes = keyStorage.getCekBytes();
        return cipher.doFinal(cekBytes); 
    }

    protected abstract Cipher getCipherProvider() throws Exception;

}
