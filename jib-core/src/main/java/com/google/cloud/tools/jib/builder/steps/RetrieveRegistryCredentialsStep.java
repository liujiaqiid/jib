/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder.steps;

import com.google.cloud.tools.jib.Timer;
import com.google.cloud.tools.jib.async.AsyncStep;
import com.google.cloud.tools.jib.builder.BuildConfiguration;
import com.google.cloud.tools.jib.builder.BuildLogger;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.registry.credentials.DockerConfigCredentialRetriever;
import com.google.cloud.tools.jib.registry.credentials.DockerCredentialHelper;
import com.google.cloud.tools.jib.registry.credentials.NonexistentDockerCredentialHelperException;
import com.google.cloud.tools.jib.registry.credentials.NonexistentServerUrlDockerCredentialHelperException;
import com.google.cloud.tools.jib.registry.credentials.RegistryCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Attempts to retrieve registry credentials. */
class RetrieveRegistryCredentialsStep implements AsyncStep<Authorization>, Callable<Authorization> {

  private static final String DESCRIPTION = "Retrieving registry credentials for %s";

  /**
   * Defines common credential helpers to use as defaults. Maps from registry suffix to credential
   * helper suffix.
   */
  private static final ImmutableMap<String, String> COMMON_CREDENTIAL_HELPERS =
      ImmutableMap.of("gcr.io", "gcr", "amazonaws.com", "ecr-login");

  private static final ImmutableList<String> DOCKER_HUB_REGISTRIES =
      ImmutableList.of("registry.hub.docker.com", "index.docker.io");

  /** Retrieves credentials for the base image. */
  static RetrieveRegistryCredentialsStep forBaseImage(
      ListeningExecutorService listeningExecutorService, BuildConfiguration buildConfiguration) {
    return new RetrieveRegistryCredentialsStep(
        listeningExecutorService,
        buildConfiguration.getBuildLogger(),
        buildConfiguration.getBaseImageRegistry(),
        buildConfiguration.getBaseImageCredentialHelperName(),
        buildConfiguration.getKnownBaseRegistryCredentials());
  }

  /** Retrieves credentials for the target image. */
  static RetrieveRegistryCredentialsStep forTargetImage(
      ListeningExecutorService listeningExecutorService, BuildConfiguration buildConfiguration) {
    return new RetrieveRegistryCredentialsStep(
        listeningExecutorService,
        buildConfiguration.getBuildLogger(),
        buildConfiguration.getTargetImageRegistry(),
        buildConfiguration.getTargetImageCredentialHelperName(),
        buildConfiguration.getKnownTargetRegistryCredentials());
  }

  private final BuildLogger buildLogger;
  private final String registry;
  @Nullable private final String credentialHelperSuffix;
  @Nullable private final RegistryCredentials knownRegistryCredentials;
  private final BiFunction<String, String, DockerCredentialHelper> dockerCredentialHelperFactory;
  private final Function<String, DockerConfigCredentialRetriever> dockerConfigCredentialRetrieverFactory;

  private final ListenableFuture<Authorization> listenableFuture;

  @VisibleForTesting
  RetrieveRegistryCredentialsStep(
      ListeningExecutorService listeningExecutorService,
      BuildLogger buildLogger,
      String registry,
      @Nullable String credentialHelperSuffix,
      @Nullable RegistryCredentials knownRegistryCredentials,
      BiFunction<String, String, DockerCredentialHelper> dockerCredentialHelperFactory,
      Function<String, DockerConfigCredentialRetriever> dockerConfigCredentialRetrieverFactory) {
    this.buildLogger = buildLogger;
    this.registry = registry;
    this.credentialHelperSuffix = credentialHelperSuffix;
    this.knownRegistryCredentials = knownRegistryCredentials;
    this.dockerCredentialHelperFactory = dockerCredentialHelperFactory;
    this.dockerConfigCredentialRetrieverFactory = dockerConfigCredentialRetrieverFactory;

    listenableFuture = listeningExecutorService.submit(this);
  }

  /** Instantiate with {@link #forBaseImage} or {@link #forTargetImage}. */
  private RetrieveRegistryCredentialsStep(
      ListeningExecutorService listeningExecutorService,
      BuildLogger buildLogger,
      String registry,
      @Nullable String credentialHelperSuffix,
      @Nullable RegistryCredentials knownRegistryCredentials) {
    this(
        listeningExecutorService,
        buildLogger,
        registry,
        credentialHelperSuffix,
        knownRegistryCredentials,
        DockerCredentialHelper::new,
        DockerConfigCredentialRetriever::new);
  }

  @Override
  public ListenableFuture<Authorization> getFuture() {
    return listenableFuture;
  }

  @Override
  @Nullable
  public Authorization call() throws IOException, NonexistentDockerCredentialHelperException {
    Authorization authorization = getAuthorization(registry);

    if (authorization == null && isDockerHubRegistry(registry)) {
      buildLogger.info("Registry is Docker Hub. Checking if credentials for other Docker Hub alises are configured.");
      return findOtherDockerHubCredential(registry);
    }
    return authorization;
  }

  private Authorization getAuthorization(String registry)
      throws IOException, NonexistentDockerCredentialHelperException {
    buildLogger.lifecycle(String.format(DESCRIPTION, registry) + "...");

    try (Timer ignored = new Timer(buildLogger, String.format(DESCRIPTION, registry))) {
      // Tries to get registry credentials from Docker credential helpers.
      if (credentialHelperSuffix != null) {
        Authorization authorization = retrieveFromCredentialHelper(credentialHelperSuffix);
        if (authorization != null) {
          return authorization;
        }
      }

      // Tries to get registry credentials from known registry credentials.
      if (knownRegistryCredentials != null) {
        logGotCredentialsFrom(knownRegistryCredentials.getCredentialSource());
        return knownRegistryCredentials.getAuthorization();
      }

      // Tries to infer common credential helpers for known registries.
      for (String registrySuffix : COMMON_CREDENTIAL_HELPERS.keySet()) {
        if (registry.endsWith(registrySuffix)) {
          try {
            String commonCredentialHelper = COMMON_CREDENTIAL_HELPERS.get(registrySuffix);
            if (commonCredentialHelper == null) {
              throw new IllegalStateException("No COMMON_CREDENTIAL_HELPERS should be null");
            }
            Authorization authorization = retrieveFromCredentialHelper(commonCredentialHelper);
            if (authorization != null) {
              return authorization;
            }

          } catch (NonexistentDockerCredentialHelperException ex) {
            if (ex.getMessage() != null) {
              // Warns the user that the specified (or inferred) credential helper is not on the
              // system.
              buildLogger.warn(ex.getMessage());
            }
          }
        }
      }

      // Tries to get registry credentials from the Docker config.
      try {
        Authorization dockerConfigAuthorization = dockerConfigCredentialRetrieverFactory.apply(registry).retrieve();
        if (dockerConfigAuthorization != null) {
          buildLogger.info("Using credentials from Docker config for " + registry);
          return dockerConfigAuthorization;
        }

      } catch (IOException ex) {
        buildLogger.info("Unable to parse Docker config");
      }

      /*
       * If no credentials found, give an info (not warning because in most cases, the base image is
       * public and does not need extra credentials) and return null.
       */
      buildLogger.info("No credentials could be retrieved for registry " + registry);

      return null;
    }
  }

  @VisibleForTesting
  static boolean isDockerHubRegistry(String registry) {
    return DOCKER_HUB_REGISTRIES.stream().anyMatch(registry::equals);
  }

  @VisibleForTesting
  Authorization findOtherDockerHubCredential(String dockerHubRegistry)
      throws IOException, NonexistentDockerCredentialHelperException {
    for (String otherRegistry : DOCKER_HUB_REGISTRIES) {
      if (!otherRegistry.equals(dockerHubRegistry)) {
        Authorization authorization = getAuthorization(otherRegistry);
        if (authorization != null) {
          return authorization;
        }
      }
    }
    return null;
  }

  /**
   * Attempts to retrieve authorization for the registry using {@code
   * docker-credential-[credentialHelperSuffix]}.
   */
  @VisibleForTesting
  @Nullable
  Authorization retrieveFromCredentialHelper(String credentialHelperSuffix)
      throws NonexistentDockerCredentialHelperException, IOException {
    buildLogger.info("Checking credentials from docker-credential-" + credentialHelperSuffix);

    try {
      Authorization authorization =
          dockerCredentialHelperFactory.apply(registry, credentialHelperSuffix).retrieve();
      logGotCredentialsFrom("docker-credential-" + credentialHelperSuffix);
      return authorization;

    } catch (NonexistentServerUrlDockerCredentialHelperException ex) {
      buildLogger.info(
          "No credentials for " + registry + " in docker-credential-" + credentialHelperSuffix);
      return null;
    }
  }

  private void logGotCredentialsFrom(String credentialSource) {
    buildLogger.info("Using " + credentialSource + " for " + registry);
  }
}
