/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.experiment;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.google.common.annotations.VisibleForTesting;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicExperimentEnrollmentConfiguration;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicPreRegistrationExperimentEnrollmentConfiguration;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.util.Util;

public class ExperimentEnrollmentManager {

  private final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager;
  private final Random random;


  public ExperimentEnrollmentManager(
      final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager) {
    this(dynamicConfigurationManager, ThreadLocalRandom.current());
  }

  @VisibleForTesting
  ExperimentEnrollmentManager(
      final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager,
      final Random random) {
    this.dynamicConfigurationManager = dynamicConfigurationManager;
    this.random = random;
  }

  public boolean isEnrolled(final UUID accountUuid, final String experimentName) {

    final Optional<DynamicExperimentEnrollmentConfiguration> maybeConfiguration = dynamicConfigurationManager
        .getConfiguration().getExperimentEnrollmentConfiguration(experimentName);

    return maybeConfiguration
        .map(config -> isAccountEnrolled(accountUuid, config)
            .orElse(isEnrolled(accountUuid, config.getEnrollmentPercentage(), experimentName))
        ).orElse(false);
  }

  Optional<Boolean> isAccountEnrolled(final UUID accountUuid, DynamicExperimentEnrollmentConfiguration config) {
    if (config.getUuidSelector().getUuids().contains(accountUuid)) {
      final int r = random.nextInt(100);
      return Optional.of(r < config.getUuidSelector().getUuidEnrollmentPercentage());
    }

    return Optional.empty();
  }

  public boolean isEnrolled(final String e164, final UUID accountUuid, final String experimentName) {

    final Optional<DynamicExperimentEnrollmentConfiguration> maybeConfiguration = dynamicConfigurationManager
        .getConfiguration().getExperimentEnrollmentConfiguration(experimentName);

    return maybeConfiguration
        .flatMap(config -> isAccountEnrolled(accountUuid, config))
        .orElse(isEnrolled(e164, experimentName));
  }

  public boolean isEnrolled(final String e164, final String experimentName) {

    final Optional<DynamicPreRegistrationExperimentEnrollmentConfiguration> maybeConfiguration = dynamicConfigurationManager
        .getConfiguration().getPreRegistrationEnrollmentConfiguration(experimentName);

    return maybeConfiguration.map(config -> {

      if (config.getEnrolledE164s().contains(e164)) {
        return true;
      }

      if (config.getExcludedE164s().contains(e164)) {
        return false;
      }

      {
        final String countryCode = Util.getCountryCode(e164);

        if (config.getIncludedCountryCodes().contains(countryCode)) {
          return true;
        }

        if (config.getExcludedCountryCodes().contains(countryCode)) {
          return false;
        }
      }

      return isEnrolled(e164, config.getEnrollmentPercentage(), experimentName);

    }).orElse(false);
  }

  private boolean isEnrolled(final Object entity, final int enrollmentPercentage, final String experimentName) {
    final int enrollmentHash = ((entity.hashCode() ^ experimentName.hashCode()) & Integer.MAX_VALUE) % 100;

    return enrollmentHash < enrollmentPercentage;
  }
}
