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

package org.keycloak.testsuite.util;

import org.jboss.logging.Logger;
import org.junit.Assume;
import org.keycloak.testsuite.arquillian.AuthServerTestEnricher;

public class ContainerAssume {

    private static final Logger log = Logger.getLogger(ContainerAssume.class);

    public static void assumeNotAuthServerUndertow() {
        Assume.assumeFalse("Doesn't work on auth-server-undertow", 
                AuthServerTestEnricher.AUTH_SERVER_CONTAINER.equals(AuthServerTestEnricher.AUTH_SERVER_CONTAINER_DEFAULT));
    }
    public static void assumeAuthServerUndertow() {
        Assume.assumeTrue("Only works on auth-server-undertow",
                AuthServerTestEnricher.AUTH_SERVER_CONTAINER.equals(AuthServerTestEnricher.AUTH_SERVER_CONTAINER_DEFAULT));
    }
    

    public static void assumeNotAuthServerRemote() {
        Assume.assumeFalse("Doesn't work on auth-server-remote", 
                AuthServerTestEnricher.AUTH_SERVER_CONTAINER.equals("auth-server-remote"));
    }

    public static void assumeClusteredContainer() {
        Assume.assumeTrue(
              String.format("Ignoring test since %s is set to false",
                    AuthServerTestEnricher.AUTH_SERVER_CLUSTER_PROPERTY), AuthServerTestEnricher.AUTH_SERVER_CLUSTER);
    }
}
