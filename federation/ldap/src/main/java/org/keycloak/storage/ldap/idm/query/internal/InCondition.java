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

package org.keycloak.storage.ldap.idm.query.internal;

import org.keycloak.models.LDAPConstants;

/**
 * @author Pedro Igor
 */
class InCondition extends NamedParameterCondition {

    private final Object[] valuesToCompare;

    public InCondition(String name, Object[] valuesToCompare) {
        super(name);
        this.valuesToCompare = valuesToCompare;
    }

    @Override
    public void applyCondition(StringBuilder filter) {

        filter.append("(&(");

        for (int i = 0; i< valuesToCompare.length; i++) {
            Object value = new OctetStringEncoder().encode(valuesToCompare[i], isBinary());

            filter.append("(").append(getParameterName()).append(LDAPConstants.EQUAL).append(value).append(")");
        }

        filter.append("))");
    }
}

