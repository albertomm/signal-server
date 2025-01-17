package org.whispersystems.textsecuregcm.grpc;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.signal.chat.profile.SetProfileRequest.AvatarChange;
import org.signal.chat.profile.ProfileGrpc;
import org.signal.chat.profile.SetProfileRequest;
import org.signal.chat.profile.SetProfileResponse;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.whispersystems.textsecuregcm.auth.grpc.MockAuthenticationInterceptor;
import org.whispersystems.textsecuregcm.configuration.BadgeConfiguration;
import org.whispersystems.textsecuregcm.configuration.BadgesConfiguration;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicPaymentsConfiguration;
import org.whispersystems.textsecuregcm.entities.BadgeSvg;
import org.whispersystems.textsecuregcm.s3.PolicySigner;
import org.whispersystems.textsecuregcm.s3.PostPolicyGenerator;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.storage.ProfilesManager;
import org.whispersystems.textsecuregcm.storage.VersionedProfile;
import org.whispersystems.textsecuregcm.tests.util.AuthHelper;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ProfileGrpcServiceTest {
  private static final UUID AUTHENTICATED_ACI = UUID.randomUUID();
  private static final long AUTHENTICATED_DEVICE_ID = Device.MASTER_ID;
  private static final String S3_BUCKET = "profileBucket";
  private static final String VERSION = "someVersion";
  private static final byte[] VALID_NAME = new byte[81];
  private ProfilesManager profilesManager;
  private DynamicPaymentsConfiguration dynamicPaymentsConfiguration;
  private S3AsyncClient asyncS3client;
  private VersionedProfile profile;
  private Account account;
  private ProfileGrpc.ProfileBlockingStub profileBlockingStub;

  @RegisterExtension
  static final GrpcServerExtension GRPC_SERVER_EXTENSION = new GrpcServerExtension();

  @BeforeEach
  void setup() {
    profilesManager = mock(ProfilesManager.class);
    dynamicPaymentsConfiguration = mock(DynamicPaymentsConfiguration.class);
    asyncS3client = mock(S3AsyncClient.class);
    profile = mock(VersionedProfile.class);
    account = mock(Account.class);

    final AccountsManager accountsManager = mock(AccountsManager.class);
    @SuppressWarnings("unchecked") final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager = mock(DynamicConfigurationManager.class);
    final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);
    final PolicySigner policySigner = new PolicySigner("accessSecret", "us-west-1");
    final PostPolicyGenerator policyGenerator = new PostPolicyGenerator("us-west-1", "profile-bucket", "accessKey");
    final BadgesConfiguration badgesConfiguration = new BadgesConfiguration(
        List.of(new BadgeConfiguration(
            "TEST",
            "other",
            List.of("l", "m", "h", "x", "xx", "xxx"),
            "SVG",
            List.of(
                new BadgeSvg("sl", "sd"),
                new BadgeSvg("ml", "md"),
                new BadgeSvg("ll", "ld")
            )
        )),
        List.of("TEST1"),
        Map.of(1L, "TEST1", 2L, "TEST2", 3L, "TEST3")
    );
    final String phoneNumber = PhoneNumberUtil.getInstance().format(
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        PhoneNumberUtil.PhoneNumberFormat.E164);

    profileBlockingStub = ProfileGrpc.newBlockingStub(GRPC_SERVER_EXTENSION.getChannel());

    final ProfileGrpcService profileGrpcService = new ProfileGrpcService(
        Clock.systemUTC(),
        accountsManager,
        profilesManager,
        dynamicConfigurationManager,
        badgesConfiguration,
        asyncS3client,
        policyGenerator,
        policySigner,
        S3_BUCKET
    );

    final MockAuthenticationInterceptor mockAuthenticationInterceptor = new MockAuthenticationInterceptor();
    mockAuthenticationInterceptor.setAuthenticatedDevice(AUTHENTICATED_ACI, AUTHENTICATED_DEVICE_ID);

    GRPC_SERVER_EXTENSION.getServiceRegistry()
        .addService(ServerInterceptors.intercept(profileGrpcService, mockAuthenticationInterceptor));

    when(dynamicConfigurationManager.getConfiguration()).thenReturn(dynamicConfiguration);
    when(dynamicConfiguration.getPaymentsConfiguration()).thenReturn(dynamicPaymentsConfiguration);

    when(account.getUuid()).thenReturn(AUTHENTICATED_ACI);
    when(account.getNumber()).thenReturn(phoneNumber);
    when(account.getBadges()).thenReturn(Collections.emptyList());

    when(profile.paymentAddress()).thenReturn(null);
    when(profile.avatar()).thenReturn("");

    when(accountsManager.getByAccountIdentifierAsync(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(account)));
    when(accountsManager.updateAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

    when(profilesManager.getAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));
    when(profilesManager.setAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

    when(dynamicConfigurationManager.getConfiguration()).thenReturn(dynamicConfiguration);
    when(dynamicConfiguration.getPaymentsConfiguration()).thenReturn(dynamicPaymentsConfiguration);
    when(dynamicPaymentsConfiguration.getDisallowedPrefixes()).thenReturn(Collections.emptyList());

    when(asyncS3client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  void setProfile() throws InvalidInputException {
    final byte[] commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AUTHENTICATED_ACI)).serialize();
    final byte[] validAboutEmoji = new byte[60];
    final byte[] validAbout = new byte[540];
    final byte[] validPaymentAddress = new byte[582];

    final SetProfileRequest request = SetProfileRequest.newBuilder()
        .setVersion(VERSION)
        .setName(ByteString.copyFrom(VALID_NAME))
        .setAvatarChange(AvatarChange.AVATAR_CHANGE_UNCHANGED)
        .setAboutEmoji(ByteString.copyFrom(validAboutEmoji))
        .setAbout(ByteString.copyFrom(validAbout))
        .setPaymentAddress(ByteString.copyFrom(validPaymentAddress))
        .setCommitment(ByteString.copyFrom(commitment))
        .build();

    //noinspection ResultOfMethodCallIgnored
    profileBlockingStub.setProfile(request);

    final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

    verify(profilesManager).setAsync(eq(account.getUuid()), profileArgumentCaptor.capture());

    final VersionedProfile profile = profileArgumentCaptor.getValue();

    assertThat(profile.commitment()).isEqualTo(commitment);
    assertThat(profile.avatar()).isNull();
    assertThat(profile.version()).isEqualTo(VERSION);
    assertThat(profile.name()).isEqualTo(VALID_NAME);
    assertThat(profile.aboutEmoji()).isEqualTo(validAboutEmoji);
    assertThat(profile.about()).isEqualTo(validAbout);
    assertThat(profile.paymentAddress()).isEqualTo(validPaymentAddress);
  }

  @ParameterizedTest
  @MethodSource
  void setProfileUpload(AvatarChange avatarChange, boolean hasPreviousProfile,
      boolean expectHasS3UploadPath, boolean expectDeleteS3Object) throws InvalidInputException {
    final String currentAvatar = "profiles/currentAvatar";
    final byte[] commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AUTHENTICATED_ACI)).serialize();

    final SetProfileRequest request = SetProfileRequest.newBuilder()
        .setVersion(VERSION)
        .setName(ByteString.copyFrom(VALID_NAME))
        .setAvatarChange(avatarChange)
        .setCommitment(ByteString.copyFrom(commitment))
        .build();

    when(profile.avatar()).thenReturn(currentAvatar);

    when(profilesManager.getAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(
        hasPreviousProfile ? Optional.of(profile) : Optional.empty()));
    when(profilesManager.setAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

    SetProfileResponse response = profileBlockingStub.setProfile(request);

    if (expectHasS3UploadPath) {
      assertTrue(response.getAttributes().getPath().startsWith("profiles/"));
    } else {
      assertEquals(response.getAttributes().getPath(), "");
    }

    if (expectDeleteS3Object) {
      verify(asyncS3client).deleteObject(DeleteObjectRequest.builder()
          .bucket(S3_BUCKET)
          .key(currentAvatar)
          .build());
    } else {
      verifyNoInteractions(asyncS3client);
    }
  }

  private static Stream<Arguments> setProfileUpload() {
    return Stream.of(
        // Upload new avatar, no previous avatar
        Arguments.of(AvatarChange.AVATAR_CHANGE_UPDATE, false, true, false),
        // Upload new avatar, has previous avatar
        Arguments.of(AvatarChange.AVATAR_CHANGE_UPDATE, true, true, true),
        // Clear avatar on profile, no previous avatar
        Arguments.of(AvatarChange.AVATAR_CHANGE_CLEAR, false, false, false),
        // Clear avatar on profile, has previous avatar
        Arguments.of(AvatarChange.AVATAR_CHANGE_CLEAR, true, false, true),
        // Set same avatar, no previous avatar
        Arguments.of(AvatarChange.AVATAR_CHANGE_UNCHANGED, false, false, false),
        // Set same avatar, has previous avatar
        Arguments.of(AvatarChange.AVATAR_CHANGE_UNCHANGED, true, false, false)
    );
  }

  @ParameterizedTest
  @MethodSource
  void setProfileInvalidRequestData(SetProfileRequest request) {
    final StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> profileBlockingStub.setProfile(request));

    assertEquals(Status.INVALID_ARGUMENT.getCode(), exception.getStatus().getCode());
  }

  private static Stream<Arguments> setProfileInvalidRequestData() throws InvalidInputException{
    final byte[] commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO)).serialize();
    final byte[] invalidValue = new byte[42];

    final SetProfileRequest prototypeRequest = SetProfileRequest.newBuilder()
        .setVersion(VERSION)
        .setName(ByteString.copyFrom(VALID_NAME))
        .setCommitment(ByteString.copyFrom(commitment))
        .build();

    return Stream.of(
        // Missing version
        Arguments.of(SetProfileRequest.newBuilder(prototypeRequest)
            .clearVersion()
            .build()),
        // Missing name
        Arguments.of(SetProfileRequest.newBuilder(prototypeRequest)
            .clearName()
            .build()),
        // Invalid name length
        Arguments.of(SetProfileRequest.newBuilder(prototypeRequest)
            .setName(ByteString.copyFrom(invalidValue))
            .build()),
        // Invalid about emoji length
        Arguments.of(SetProfileRequest.newBuilder(prototypeRequest)
            .setAboutEmoji(ByteString.copyFrom(invalidValue))
            .build()),
        // Invalid about length
        Arguments.of(SetProfileRequest.newBuilder(prototypeRequest)
            .setAbout(ByteString.copyFrom(invalidValue))
            .build()),
        // Invalid payment address
        Arguments.of(SetProfileRequest.newBuilder(prototypeRequest)
            .setPaymentAddress(ByteString.copyFrom(invalidValue))
            .build()),
        // Missing profile commitment
        Arguments.of(SetProfileRequest.newBuilder()
            .clearCommitment()
            .build())
    );
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void setPaymentAddressDisallowedCountry(boolean hasExistingPaymentAddress) throws InvalidInputException {
    final Phonenumber.PhoneNumber disallowedPhoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("CU");
    final byte[] commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AUTHENTICATED_ACI)).serialize();

    final byte[] validPaymentAddress = new byte[582];
    if (hasExistingPaymentAddress) {
      when(profile.paymentAddress()).thenReturn(validPaymentAddress);
    }

    final SetProfileRequest request = SetProfileRequest.newBuilder()
        .setVersion(VERSION)
        .setName(ByteString.copyFrom(VALID_NAME))
        .setAvatarChange(AvatarChange.AVATAR_CHANGE_UNCHANGED)
        .setPaymentAddress(ByteString.copyFrom(validPaymentAddress))
        .setCommitment(ByteString.copyFrom(commitment))
        .build();
    final String disallowedCountryCode = String.format("+%d", disallowedPhoneNumber.getCountryCode());
    when(dynamicPaymentsConfiguration.getDisallowedPrefixes()).thenReturn(List.of(disallowedCountryCode));
    when(account.getNumber()).thenReturn(PhoneNumberUtil.getInstance().format(
        disallowedPhoneNumber,
        PhoneNumberUtil.PhoneNumberFormat.E164));
    when(profilesManager.getAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));

    if (hasExistingPaymentAddress) {
      assertDoesNotThrow(() -> profileBlockingStub.setProfile(request),
          "Payment address changes in disallowed countries should still be allowed if the account already has a valid payment address");
    } else {
      final StatusRuntimeException exception =
          assertThrows(StatusRuntimeException.class, () -> profileBlockingStub.setProfile(request));
      assertEquals(Status.PERMISSION_DENIED.getCode(), exception.getStatus().getCode());
    }
  }
}
