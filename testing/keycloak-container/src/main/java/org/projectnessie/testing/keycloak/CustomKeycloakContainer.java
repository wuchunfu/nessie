/*
 * Copyright (C) 2023 Dremio
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
package org.projectnessie.testing.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dasniko.testcontainers.keycloak.ExtendableKeycloakContainer;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.RolesRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

public class CustomKeycloakContainer extends ExtendableKeycloakContainer<CustomKeycloakContainer> {
  public static final String KEYCLOAK_REALM = "keycloak.realm";
  public static final String KEYCLOAK_USE_HTTPS = "keycloak.use.https";
  public static final String KEYCLOAK_DOCKER_IMAGE = "keycloak.docker.image";
  public static final String KEYCLOAK_DOCKER_TAG = "keycloak.docker.tag";
  public static final String KEYCLOAK_DOCKER_NETWORK_ID = "keycloak.docker.network.id";
  public static final String FEATURES_ENABLED = "keycloak.features.enabled";
  public static final String CLIENT_SECRET = "secret";

  private static final Logger LOGGER = LoggerFactory.getLogger(CustomKeycloakContainer.class);

  private static final int KEYCLOAK_PORT_HTTP = 8080;
  private static final int KEYCLOAK_PORT_HTTPS = 8443;

  private final KeycloakConfig config;

  public static KeycloakConfig.Builder builder() {
    return ImmutableKeycloakConfig.builder();
  }

  public static ClientRepresentation createServiceClient(String clientId) {
    ClientRepresentation client = new ClientRepresentation();

    client.setClientId(clientId);
    client.setPublicClient(false);
    client.setSecret(CLIENT_SECRET);
    client.setDirectAccessGrantsEnabled(true);
    client.setServiceAccountsEnabled(true);

    // required for authorization code grant
    client.setStandardFlowEnabled(true);
    client.setRedirectUris(List.of("http://localhost:*"));

    // required for device code grant
    client.setAttributes(Map.of("oauth2.device.authorization.grant.enabled", "true"));

    client.setEnabled(true);

    return client;
  }

  public static ClientRepresentation createWebAppClient(String clientId) {
    ClientRepresentation client = new ClientRepresentation();

    client.setClientId(clientId);
    client.setPublicClient(false);
    client.setSecret(CLIENT_SECRET);
    client.setRedirectUris(Collections.singletonList("*"));
    client.setEnabled(true);

    return client;
  }

  public static UserRepresentation createUser(String username, List<String> realmRoles) {
    UserRepresentation user = new UserRepresentation();

    user.setUsername(username);
    user.setEnabled(true);
    user.setCredentials(new ArrayList<>());
    user.setRealmRoles(realmRoles);
    user.setEmail(username + "@gmail.com");

    CredentialRepresentation credential = new CredentialRepresentation();

    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setValue(username);
    credential.setTemporary(false);

    user.getCredentials().add(credential);

    return user;
  }

  @Value.Immutable
  public abstract static class KeycloakConfig {

    public static final String DEFAULT_IMAGE;
    public static final String DEFAULT_TAG;

    static {
      URL resource = KeycloakConfig.class.getResource("Dockerfile-keycloak-version");
      try (InputStream in = resource.openConnection().getInputStream()) {
        String[] imageTag =
            Arrays.stream(new String(in.readAllBytes(), UTF_8).split("\n"))
                .map(String::trim)
                .filter(l -> l.startsWith("FROM "))
                .map(l -> l.substring(5).trim().split(":"))
                .findFirst()
                .orElseThrow();
        DEFAULT_IMAGE = imageTag[0];
        DEFAULT_TAG = imageTag[1];
      } catch (Exception e) {
        throw new RuntimeException("Failed to extract tag from " + resource, e);
      }
    }

    @Value.Default
    public String dockerImage() {
      return DEFAULT_IMAGE;
    }

    @Value.Default
    public String dockerTag() {
      return DEFAULT_TAG;
    }

    @Value.Default
    public String realmName() {
      return "quarkus";
    }

    @Value.Default
    public boolean useHttps() {
      return false;
    }

    @Value.Default
    public List<String> featuresEnabled() {
      return List.of("token-exchange", "preview");
    }

    @Nullable
    public abstract String dockerNetworkId();

    @Value.Default
    public Consumer<RealmRepresentation> realmConfigure() {
      return r -> {};
    }

    public interface Builder {
      @CanIgnoreReturnValue
      Builder dockerImage(String dockerImage);

      @CanIgnoreReturnValue
      Builder dockerTag(String dockerTag);

      @CanIgnoreReturnValue
      Builder dockerNetworkId(String dockerNetworkId);

      @CanIgnoreReturnValue
      Builder realmName(String realmName);

      @CanIgnoreReturnValue
      Builder useHttps(boolean useHttps);

      @CanIgnoreReturnValue
      Builder addFeaturesEnabled(String element);

      @CanIgnoreReturnValue
      Builder addFeaturesEnabled(String... elements);

      @CanIgnoreReturnValue
      Builder featuresEnabled(Iterable<String> elements);

      @CanIgnoreReturnValue
      Builder realmConfigure(Consumer<RealmRepresentation> realmConfigure);

      KeycloakConfig build();

      default Builder fromProperties(Map<String, String> initArgs) {
        initArgs(initArgs, KEYCLOAK_REALM, this::realmName);
        initArgs(initArgs, KEYCLOAK_USE_HTTPS, s -> useHttps(Boolean.parseBoolean(s)));
        initArgs(initArgs, KEYCLOAK_DOCKER_IMAGE, this::dockerImage);
        initArgs(initArgs, KEYCLOAK_DOCKER_TAG, this::dockerTag);
        initArgs(initArgs, KEYCLOAK_DOCKER_NETWORK_ID, this::dockerNetworkId);
        initArgs(initArgs, FEATURES_ENABLED, s -> addFeaturesEnabled(s.split(",")));
        return this;
      }
    }

    public CustomKeycloakContainer createContainer() {
      LOGGER.info("Using Keycloak image {}:{}", dockerImage(), dockerTag());
      return new CustomKeycloakContainer(this);
    }
  }

  private static void initArgs(
      Map<String, String> initArgs, String property, Consumer<String> consumer) {
    String value = initArgs.getOrDefault(property, System.getProperty(property));
    if (value != null) {
      consumer.accept(value);
    }
  }

  @SuppressWarnings("resource")
  public CustomKeycloakContainer(KeycloakConfig config) {
    super(config.dockerImage() + ":" + config.dockerTag());

    this.config = config;

    withNetworkAliases("keycloak");
    withFeaturesEnabled(config.featuresEnabled().toArray(new String[0]));

    // This will force all token issuer claims for the configured realm to be
    // equal to getInternalRealmUri(), and in turn equal to getTokenIssuerUri(),
    // which simplifies testing.
    withEnv("KC_HOSTNAME_URL", getInternalRootUri().toString());

    // Don't use withNetworkMode, or aliases won't work!
    // See https://github.com/testcontainers/testcontainers-java/issues/1221
    String containerNetworkId = config.dockerNetworkId();
    if (containerNetworkId != null) {
      withNetwork(new ExternalNetwork(containerNetworkId));
    }

    if (config.useHttps()) {
      LOGGER.info("Enabling TLS for Keycloak");
      useTls();
    }
  }

  @Override
  public void start() {
    LOGGER.info("Starting Keycloak...");
    super.start();
    LOGGER.info("Keycloak started, creating realm {}...", config.realmName());

    RealmRepresentation realm = createRealm();
    try (Keycloak adminClient = getKeycloakAdminClient()) {
      adminClient.realms().create(realm);
    }

    LOGGER.info("Finished setting up Keycloak, external realm auth url: {}", getExternalRealmUri());
  }

  private RealmRepresentation createRealm() {
    RealmRepresentation realm = new RealmRepresentation();

    realm.setRealm(config.realmName());
    realm.setEnabled(true);
    realm.setUsers(new ArrayList<>());
    realm.setClients(new ArrayList<>());

    realm.setAccessTokenLifespan(60); // 1 minute

    // Refresh token lifespan will be equal to the smallest value between:
    // SSO Session Idle, SSO Session Max, Client Session Idle, and Client Session Max.
    int refreshTokenLifespanSeconds = 60 * 5; // 5 minutes
    realm.setClientSessionIdleTimeout(refreshTokenLifespanSeconds);
    realm.setClientSessionMaxLifespan(refreshTokenLifespanSeconds);
    realm.setSsoSessionIdleTimeout(refreshTokenLifespanSeconds);
    realm.setSsoSessionMaxLifespan(refreshTokenLifespanSeconds);

    RolesRepresentation roles = new RolesRepresentation();
    List<RoleRepresentation> realmRoles = new ArrayList<>();

    roles.setRealm(realmRoles);
    realm.setRoles(roles);

    config.realmConfigure().accept(realm);

    return realm;
  }

  @Override
  public void stop() {
    try {
      try (Keycloak adminClient = getKeycloakAdminClient()) {
        RealmResource realm = adminClient.realm(config.realmName());
        realm.remove();
      }
    } finally {
      super.stop();
    }
  }

  /**
   * Returns the (external) URL of the Keycloak realm. This is the URL that clients outside the
   * Docker network can use to access Keycloak. This is also the URL that should be used as the
   * value of the {@code quarkus.oidc.auth-server-url} property for Quarkus applications running
   * outside the Docker network.
   */
  public URI getExternalRealmUri() {
    return URI.create(
        String.format(
            "%s://%s:%s%srealms/%s",
            config.useHttps() ? "https" : "http",
            getHost(),
            config.useHttps() ? getHttpsPort() : getHttpPort(), // mapped ports
            ensureSlashes(getContextPath()),
            config.realmName()));
  }

  /**
   * Returns the (external) URL of the Keycloak token endpoint. This is the URL that should be used
   * as the value of the {@code nessie.authentication.oauth2.token-endpoint} property for Nessie
   * clients using the OAUTH2 authentication provider, and sitting outside the Docker network.
   */
  public URI getExternalTokenEndpointUri() {
    return URI.create(
        String.format(
            "%s://%s:%s%srealms/%s/protocol/openid-connect/token",
            config.useHttps() ? "https" : "http",
            getHost(),
            config.useHttps() ? getHttpsPort() : getHttpPort(), // mapped ports
            ensureSlashes(getContextPath()),
            config.realmName()));
  }

  /**
   * Returns the (internal) root URL for Keycloak (without the context path). This is used as the
   * issuer claim for all tokens.
   */
  public URI getInternalRootUri() {
    return URI.create(
        String.format(
            "%s://keycloak:%s",
            config.useHttps() ? "https" : "http",
            config.useHttps() ? KEYCLOAK_PORT_HTTPS : KEYCLOAK_PORT_HTTP)); // non-mapped ports
  }

  /**
   * Returns the (internal) URL of the Keycloak realm. This is the URL that clients inside the
   * Docker network can use to access Keycloak. This is also the URL that should be used as the
   * value of the {@code quarkus.oidc.auth-server-url} property for Quarkus applications running
   * inside the Docker network.
   */
  public URI getInternalRealmUri() {
    return URI.create(
        String.format(
            "%s%srealms/%s",
            getInternalRootUri(), ensureSlashes(getContextPath()), config.realmName()));
  }

  public String getExternalIp() {
    return requireNonNull(
            getContainerInfo(),
            "Keycloak container object available, but container info is null. Is the Keycloak container started?")
        .getNetworkSettings()
        .getNetworks()
        .values()
        .iterator()
        .next()
        .getIpAddress();
  }

  /**
   * Returns the URI used to fill the issuer ("iss") claim for all tokens generated by this server,
   * regardless of the client IP address. Having a fixed issuer claim facilitates testing by making
   * it easier to validate tokens.
   *
   * <p>This is currently set to be the {@link #getInternalRealmUri()}, and cannot be changed.
   */
  public URI getTokenIssuerUri() {
    return getInternalRealmUri();
  }

  private static class ExternalNetwork implements Network {

    private final String networkId;

    public ExternalNetwork(String networkId) {
      this.networkId = networkId;
    }

    @Override
    public Statement apply(Statement var1, Description var2) {
      return null;
    }

    public String getId() {
      return networkId;
    }

    public void close() {
      // don't close the external network
    }
  }

  private static String ensureSlashes(String s) {
    if (!s.startsWith("/")) {
      s = "/" + s;
    }
    if (!s.endsWith("/")) {
      s = s + "/";
    }
    return s;
  }
}
