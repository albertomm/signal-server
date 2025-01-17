/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.limits;


import com.google.common.annotations.VisibleForTesting;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.redis.ClusterLuaScript;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;

public class RateLimiters extends BaseRateLimiters<RateLimiters.For> {

  public enum For implements RateLimiterDescriptor {
    BACKUP_AUTH_CHECK("backupAuthCheck", false, new RateLimiterConfig(100, Duration.ofSeconds(15))),
    SMS_DESTINATION("smsDestination", false, new RateLimiterConfig(2, Duration.ofMillis(500))),
    VOICE_DESTINATION("voxDestination", false, new RateLimiterConfig(2, Duration.ofSeconds(2))),
    VOICE_DESTINATION_DAILY("voxDestinationDaily", false, new RateLimiterConfig(10, Duration.ofSeconds(144))),
    SMS_VOICE_IP("smsVoiceIp", false, new RateLimiterConfig(1000, Duration.ofMillis(1))),
    SMS_VOICE_PREFIX("smsVoicePrefix", false, new RateLimiterConfig(1000, Duration.ofMillis(1))),
    VERIFY("verify", false, new RateLimiterConfig(6, Duration.ofMillis(500))),
    PIN("pin", false, new RateLimiterConfig(10, Duration.ofMinutes(24))),
    ATTACHMENT("attachmentCreate", false, new RateLimiterConfig(50, Duration.ofMillis(20))),
    PRE_KEYS("prekeys", false, new RateLimiterConfig(6, Duration.ofSeconds(10))),
    MESSAGES("messages", false, new RateLimiterConfig(60, Duration.ofMillis(17))),
    ALLOCATE_DEVICE("allocateDevice", false, new RateLimiterConfig(2, Duration.ofSeconds(2))),
    VERIFY_DEVICE("verifyDevice", false, new RateLimiterConfig(6, Duration.ofSeconds(10))),
    TURN("turnAllocate", false, new RateLimiterConfig(60, Duration.ofMillis(17))),
    PROFILE("profile", false, new RateLimiterConfig(4320, Duration.ofMillis(333))),
    STICKER_PACK("stickerPack", false, new RateLimiterConfig(50, Duration.ofSeconds(72))),
    ART_PACK("artPack", false, new RateLimiterConfig(50, Duration.ofSeconds(72))),
    USERNAME_LOOKUP("usernameLookup", false, new RateLimiterConfig(100, Duration.ofSeconds(15))),
    USERNAME_SET("usernameSet", false, new RateLimiterConfig(100, Duration.ofSeconds(15))),
    USERNAME_RESERVE("usernameReserve", false, new RateLimiterConfig(100, Duration.ofSeconds(15))),
    USERNAME_LINK_OPERATION("usernameLinkOperation", false, new RateLimiterConfig(10, Duration.ofMinutes(1))),
    USERNAME_LINK_LOOKUP_PER_IP("usernameLinkLookupPerIp", false, new RateLimiterConfig(100, Duration.ofSeconds(15))),
    CHECK_ACCOUNT_EXISTENCE("checkAccountExistence", false, new RateLimiterConfig(1000, Duration.ofMillis(60))),
    REGISTRATION("registration", false, new RateLimiterConfig(6, Duration.ofMillis(500))),
    VERIFICATION_PUSH_CHALLENGE("verificationPushChallenge", false, new RateLimiterConfig(5, Duration.ofMillis(500))),
    VERIFICATION_CAPTCHA("verificationCaptcha", false, new RateLimiterConfig(10, Duration.ofMillis(500))),
    RATE_LIMIT_RESET("rateLimitReset", true, new RateLimiterConfig(2, Duration.ofMinutes(12))),
    RECAPTCHA_CHALLENGE_ATTEMPT("recaptchaChallengeAttempt", true, new RateLimiterConfig(10, Duration.ofSeconds(144))),
    RECAPTCHA_CHALLENGE_SUCCESS("recaptchaChallengeSuccess", true, new RateLimiterConfig(2, Duration.ofMinutes(12))),
    PUSH_CHALLENGE_ATTEMPT("pushChallengeAttempt", true, new RateLimiterConfig(10, Duration.ofSeconds(144))),
    PUSH_CHALLENGE_SUCCESS("pushChallengeSuccess", true, new RateLimiterConfig(2, Duration.ofMinutes(12))),
    CREATE_CALL_LINK("createCallLink", false, new RateLimiterConfig(100, Duration.ofSeconds(15))),
    INBOUND_MESSAGE_BYTES("inboundMessageBytes", true, new RateLimiterConfig(128 * 1024 * 1024, Duration.ofNanos(500_000)));

    private final String id;

    private final boolean dynamic;

    private final RateLimiterConfig defaultConfig;

    For(final String id, final boolean dynamic, final RateLimiterConfig defaultConfig) {
      this.id = id;
      this.dynamic = dynamic;
      this.defaultConfig = defaultConfig;
    }

    public String id() {
      return id;
    }

    @Override
    public boolean isDynamic() {
      return dynamic;
    }

    public RateLimiterConfig defaultConfig() {
      return defaultConfig;
    }
  }

  public static RateLimiters createAndValidate(
      final Map<String, RateLimiterConfig> configs,
      final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager,
      final FaultTolerantRedisCluster cacheCluster) {
    final RateLimiters rateLimiters = new RateLimiters(
        configs, dynamicConfigurationManager, defaultScript(cacheCluster), cacheCluster, Clock.systemUTC());
    rateLimiters.validateValuesAndConfigs();
    return rateLimiters;
  }

  @VisibleForTesting
  RateLimiters(
      final Map<String, RateLimiterConfig> configs,
      final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager,
      final ClusterLuaScript validateScript,
      final FaultTolerantRedisCluster cacheCluster,
      final Clock clock) {
    super(For.values(), configs, dynamicConfigurationManager, validateScript, cacheCluster, clock);
  }

  public RateLimiter getAllocateDeviceLimiter() {
    return forDescriptor(For.ALLOCATE_DEVICE);
  }

  public RateLimiter getVerifyDeviceLimiter() {
    return forDescriptor(For.VERIFY_DEVICE);
  }

  public RateLimiter getMessagesLimiter() {
    return forDescriptor(For.MESSAGES);
  }

  public RateLimiter getPreKeysLimiter() {
    return forDescriptor(For.PRE_KEYS);
  }

  public RateLimiter getAttachmentLimiter() {
    return forDescriptor(For.ATTACHMENT);
  }

  public RateLimiter getSmsDestinationLimiter() {
    return forDescriptor(For.SMS_DESTINATION);
  }

  public RateLimiter getSmsVoiceIpLimiter() {
    return forDescriptor(For.SMS_VOICE_IP);
  }

  public RateLimiter getSmsVoicePrefixLimiter() {
    return forDescriptor(For.SMS_VOICE_PREFIX);
  }

  public RateLimiter getVoiceDestinationLimiter() {
    return forDescriptor(For.VOICE_DESTINATION);
  }

  public RateLimiter getVoiceDestinationDailyLimiter() {
    return forDescriptor(For.VOICE_DESTINATION_DAILY);
  }

  public RateLimiter getVerifyLimiter() {
    return forDescriptor(For.VERIFY);
  }

  public RateLimiter getPinLimiter() {
    return forDescriptor(For.PIN);
  }

  public RateLimiter getTurnLimiter() {
    return forDescriptor(For.TURN);
  }

  public RateLimiter getProfileLimiter() {
    return forDescriptor(For.PROFILE);
  }

  public RateLimiter getStickerPackLimiter() {
    return forDescriptor(For.STICKER_PACK);
  }

  public RateLimiter getArtPackLimiter() {
    return forDescriptor(For.ART_PACK);
  }

  public RateLimiter getUsernameLookupLimiter() {
    return forDescriptor(For.USERNAME_LOOKUP);
  }

  public RateLimiter getUsernameSetLimiter() {
    return forDescriptor(For.USERNAME_SET);
  }

  public RateLimiter getUsernameReserveLimiter() {
    return forDescriptor(For.USERNAME_RESERVE);
  }

  public RateLimiter getCheckAccountExistenceLimiter() {
    return forDescriptor(For.CHECK_ACCOUNT_EXISTENCE);
  }

  public RateLimiter getRegistrationLimiter() {
    return forDescriptor(For.REGISTRATION);
  }

  public RateLimiter getRateLimitResetLimiter() {
    return forDescriptor(For.RATE_LIMIT_RESET);
  }

  public RateLimiter getRecaptchaChallengeAttemptLimiter() {
    return forDescriptor(For.RECAPTCHA_CHALLENGE_ATTEMPT);
  }

  public RateLimiter getRecaptchaChallengeSuccessLimiter() {
    return forDescriptor(For.RECAPTCHA_CHALLENGE_SUCCESS);
  }

  public RateLimiter getPushChallengeAttemptLimiter() {
    return forDescriptor(For.PUSH_CHALLENGE_ATTEMPT);
  }

  public RateLimiter getPushChallengeSuccessLimiter() {
    return forDescriptor(For.PUSH_CHALLENGE_SUCCESS);
  }

  public RateLimiter getVerificationPushChallengeLimiter() {
    return forDescriptor(For.VERIFICATION_PUSH_CHALLENGE);
  }

  public RateLimiter getVerificationCaptchaLimiter() {
    return forDescriptor(For.VERIFICATION_CAPTCHA);
  }

  public RateLimiter getCreateCallLinkLimiter() {
    return forDescriptor(For.CREATE_CALL_LINK);
  }

  public RateLimiter getInboundMessageBytes() {
    return forDescriptor(For.INBOUND_MESSAGE_BYTES);
  }
}
