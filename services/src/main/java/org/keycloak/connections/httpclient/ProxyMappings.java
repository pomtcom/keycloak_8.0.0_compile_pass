/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
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
package org.keycloak.connections.httpclient;

import org.apache.http.HttpHost;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link ProxyMappings} describes an ordered mapping for hostname regex patterns to a {@link HttpHost} proxy.
 * <p>
 * Mappings can be created via {@link #valueOf(String...)} or {@link #valueOf(List)}.
 * For a description of the mapping format see {@link ProxyMapping#valueOf(String)}
 *
 * @author <a href="mailto:thomas.darimont@gmail.com">Thomas Darimont</a>
 */
public class ProxyMappings {

  private static final ProxyMappings EMPTY_MAPPING = valueOf(Collections.emptyList());

  private final List<ProxyMapping> entries;

  /**
   * Creates a {@link ProxyMappings} from the provided {@link ProxyMapping Entries}.
   *
   * @param entries
   */
  public ProxyMappings(List<ProxyMapping> entries) {
    this.entries = Collections.unmodifiableList(entries);
  }

  /**
   * Creates a new  {@link ProxyMappings} from the provided {@code List} of proxy mapping strings.
   * <p>
   *
   * @param proxyMappings
   */
  public static ProxyMappings valueOf(List<String> proxyMappings) {

    if (proxyMappings == null || proxyMappings.isEmpty()) {
      return EMPTY_MAPPING;
    }

    List<ProxyMapping> entries = proxyMappings.stream() //
      .map(ProxyMapping::valueOf) //
      .collect(Collectors.toList());

    return new ProxyMappings(entries);
  }

  /**
   * Creates a new  {@link ProxyMappings} from the provided {@code String[]} of proxy mapping strings.
   *
   * @param proxyMappings
   * @return
   * @see #valueOf(List)
   * @see ProxyMapping#valueOf(String...)
   */
  public static ProxyMappings valueOf(String... proxyMappings) {

    if (proxyMappings == null || proxyMappings.length == 0) {
      return EMPTY_MAPPING;
    }

    return valueOf(Arrays.asList(proxyMappings));
  }


  public boolean isEmpty() {
    return this.entries.isEmpty();
  }

  /**
   * @param hostname
   * @return the {@link HttpHost} proxy associated with the first matching hostname {@link Pattern}
   * or {@literal null} if none matches.
   */
  public HttpHost getProxyFor(String hostname) {

    Objects.requireNonNull(hostname, "hostname");

    return entries.stream() //
      .filter(e -> e.matches(hostname)) //
      .findFirst() //
      .map(ProxyMapping::getProxy) //
      .orElse(null);
  }

  /**
   * {@link ProxyMapping} describes a Proxy Mapping with a Hostname {@link Pattern}
   * that is mapped to a proxy {@link HttpHost}.
   */
  public static class ProxyMapping {

    public static final String NO_PROXY = "NO_PROXY";
    private static final String DELIMITER = ";";

    private final Pattern hostnamePattern;

    private final HttpHost proxy;

    public ProxyMapping(Pattern hostnamePattern, HttpHost proxy) {
      this.hostnamePattern = hostnamePattern;
      this.proxy = proxy;
    }

    public Pattern getHostnamePattern() {
      return hostnamePattern;
    }

    public HttpHost getProxy() {
      return proxy;
    }

    public boolean matches(String hostname) {
      return getHostnamePattern().matcher(hostname).matches();
    }

    /**
     * Parses a mapping string into an {@link ProxyMapping}.
     * <p>
     * A proxy mapping string must have the following format: {@code hostnameRegex;www-proxy-uri}
     * with semicolon as a delimiter.</p>
     * <p>
     * If no proxy should be used for a host pattern then use {@code NO_PROXY} as www-proxy-uri.
     * </p>
     * <p>Examples:
     * <pre>
     * {@code
     *
     * .*\.(google\.com|googleapis\.com);http://www-proxy.acme.corp.com:8080
     * .*\.acme\.corp\.com;NO_PROXY
     * .*;http://fallback:8080
     * }
     * </pre>
     * </p>
     *
     * @param mapping
     * @return
     */
    public static ProxyMapping valueOf(String mapping) {

      String[] mappingTokens = mapping.split(DELIMITER);

      String hostPatternRegex = mappingTokens[0];
      String proxyUriString = mappingTokens[1];

      Pattern hostPattern = Pattern.compile(hostPatternRegex);
      HttpHost proxyHost = toProxyHost(proxyUriString);

      return new ProxyMapping(hostPattern, proxyHost);
    }

    private static HttpHost toProxyHost(String proxyUriString) {

      if (NO_PROXY.equals(proxyUriString)) {
        return null;
      }

      URI uri = URI.create(proxyUriString);
      return new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
    }

    @Override
    public String toString() {
      return "ProxyMapping{" +
        "hostnamePattern=" + hostnamePattern +
        ", proxy=" + proxy +
        '}';
    }
  }
}
