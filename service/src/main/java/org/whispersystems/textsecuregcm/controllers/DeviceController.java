/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import io.dropwizard.auth.Auth;
import io.lettuce.core.SetArgs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.server.ContainerRequest;
import org.whispersystems.textsecuregcm.auth.LinkedDeviceRefreshRequirementProvider;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.auth.BasicAuthorizationHeader;
import org.whispersystems.textsecuregcm.auth.ChangesLinkedDevices;
import org.whispersystems.textsecuregcm.entities.AccountAttributes;
import org.whispersystems.textsecuregcm.entities.DeviceActivationRequest;
import org.whispersystems.textsecuregcm.entities.DeviceInfo;
import org.whispersystems.textsecuregcm.entities.DeviceInfoList;
import org.whispersystems.textsecuregcm.entities.DeviceResponse;
import org.whispersystems.textsecuregcm.entities.LinkDeviceRequest;
import org.whispersystems.textsecuregcm.entities.PreKeySignatureValidator;
import org.whispersystems.textsecuregcm.entities.ProvisioningMessage;
import org.whispersystems.textsecuregcm.entities.SetPublicKeyRequest;
import org.whispersystems.textsecuregcm.identity.IdentityType;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.ClientPublicKeysManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.Device.DeviceCapabilities;
import org.whispersystems.textsecuregcm.storage.DeviceSpec;
import org.whispersystems.textsecuregcm.util.VerificationCode;
import org.whispersystems.websocket.auth.Mutable;
import org.whispersystems.websocket.auth.ReadOnly;

@Path("/v1/devices")
@Tag(name = "Devices")
public class DeviceController {

  static final int MAX_DEVICES = 6;

  private final Key verificationTokenKey;
  private final AccountsManager accounts;
  private final ClientPublicKeysManager clientPublicKeysManager;
  private final RateLimiters rateLimiters;
  private final FaultTolerantRedisCluster usedTokenCluster;
  private final Map<String, Integer> maxDeviceConfiguration;

  private final Clock clock;

  private static final String VERIFICATION_TOKEN_ALGORITHM = "HmacSHA256";

  @VisibleForTesting
  static final Duration TOKEN_EXPIRATION_DURATION = Duration.ofMinutes(10);

  public DeviceController(byte[] linkDeviceSecret,
      AccountsManager accounts,
      ClientPublicKeysManager clientPublicKeysManager,
      RateLimiters rateLimiters,
      FaultTolerantRedisCluster usedTokenCluster,
      Map<String, Integer> maxDeviceConfiguration, final Clock clock) {
    this.verificationTokenKey = new SecretKeySpec(linkDeviceSecret, VERIFICATION_TOKEN_ALGORITHM);
    this.accounts = accounts;
    this.clientPublicKeysManager = clientPublicKeysManager;
    this.rateLimiters = rateLimiters;
    this.usedTokenCluster = usedTokenCluster;
    this.maxDeviceConfiguration = maxDeviceConfiguration;
    this.clock = clock;

    // Fail fast: reject bad keys
    try {
      final Mac mac = Mac.getInstance(VERIFICATION_TOKEN_ALGORITHM);
      mac.init(verificationTokenKey);
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError("All Java implementations must support HmacSHA256", e);
    } catch (final InvalidKeyException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public DeviceInfoList getDevices(@ReadOnly @Auth AuthenticatedDevice auth) {
    List<DeviceInfo> devices = new LinkedList<>();

    for (Device device : auth.getAccount().getDevices()) {
      devices.add(new DeviceInfo(device.getId(), device.getName(),
          device.getLastSeen(), device.getCreated()));
    }

    return new DeviceInfoList(devices);
  }

  @DELETE
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/{device_id}")
  @ChangesLinkedDevices
  public void removeDevice(@Mutable @Auth AuthenticatedDevice auth, @PathParam("device_id") byte deviceId) {
    if (auth.getAuthenticatedDevice().getId() != Device.PRIMARY_ID &&
        auth.getAuthenticatedDevice().getId() != deviceId) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    if (deviceId == Device.PRIMARY_ID) {
      throw new ForbiddenException();
    }

    accounts.removeDevice(auth.getAccount(), deviceId).join();
  }

  /**
   * Generates a signed device-linking token. Generally, primary devices will include the signed device-linking token in
   * a provisioning message to a new device, and then the new device will include the token in its request to
   * {@link #linkDevice(BasicAuthorizationHeader, String, LinkDeviceRequest, ContainerRequest)}.
   *
   * @param auth the authenticated account/device
   *
   * @return a signed device-linking token
   *
   * @throws RateLimitExceededException if the caller has made too many calls to this method in a set amount of time
   * @throws DeviceLimitExceededException if the authenticated account has already reached the maximum number of linked
   * devices
   *
   * @see ProvisioningController#sendProvisioningMessage(AuthenticatedDevice, String, ProvisioningMessage, String)
   */
  @GET
  @Path("/provisioning/code")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Generate a signed device-linking token",
      description = """
          Generate a signed device-linking token for transmission to a pending linked device via a provisioning message.
          """)
  @ApiResponse(responseCode="200", description="Token was generated successfully", useReturnTypeSchema=true)
  @ApiResponse(responseCode = "411", description = "The authenticated account already has the maximum allowed number of linked devices")
  @ApiResponse(responseCode = "429", description = "Too many attempts", headers = @Header(
      name = "Retry-After",
      description = "If present, an positive integer indicating the number of seconds before a subsequent attempt could succeed"))
  public VerificationCode createDeviceToken(@ReadOnly @Auth AuthenticatedDevice auth)
      throws RateLimitExceededException, DeviceLimitExceededException {

    final Account account = auth.getAccount();

    rateLimiters.getAllocateDeviceLimiter().validate(account.getUuid());

    int maxDeviceLimit = MAX_DEVICES;

    if (maxDeviceConfiguration.containsKey(account.getNumber())) {
      maxDeviceLimit = maxDeviceConfiguration.get(account.getNumber());
    }

    if (account.getDevices().size() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.getDevices().size(), maxDeviceLimit);
    }

    if (auth.getAuthenticatedDevice().getId() != Device.PRIMARY_ID) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    return new VerificationCode(generateVerificationToken(account.getUuid()));
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/link")
  @ChangesLinkedDevices
  @Operation(summary = "Link a device to an account",
      description = """
          Links a device to an account identified by a given phone number.
          """)
  @ApiResponse(responseCode = "200", description = "The new device was linked to the calling account", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "403", description = "The given account was not found or the given verification code was incorrect")
  @ApiResponse(responseCode = "409", description = "The new device is missing a capability supported by all other devices on the account")
  @ApiResponse(responseCode = "411", description = "The given account already has its maximum number of linked devices")
  @ApiResponse(responseCode = "422", description = "The request did not pass validation")
  @ApiResponse(responseCode = "429", description = "Too many attempts", headers = @Header(
      name = "Retry-After",
      description = "If present, an positive integer indicating the number of seconds before a subsequent attempt could succeed"))
  public DeviceResponse linkDevice(@HeaderParam(HttpHeaders.AUTHORIZATION) BasicAuthorizationHeader authorizationHeader,
      @HeaderParam(HttpHeaders.USER_AGENT) @Nullable String userAgent,
      @NotNull @Valid LinkDeviceRequest linkDeviceRequest,
      @Context ContainerRequest containerRequest)
      throws RateLimitExceededException, DeviceLimitExceededException {

    final Account account = checkVerificationToken(linkDeviceRequest.verificationCode())
        .flatMap(accounts::getByAccountIdentifier)
        .orElseThrow(ForbiddenException::new);

    final DeviceActivationRequest deviceActivationRequest = linkDeviceRequest.deviceActivationRequest();
    final AccountAttributes accountAttributes = linkDeviceRequest.accountAttributes();

    rateLimiters.getVerifyDeviceLimiter().validate(account.getUuid());

    final boolean allKeysValid =
        PreKeySignatureValidator.validatePreKeySignatures(account.getIdentityKey(IdentityType.ACI),
            List.of(deviceActivationRequest.aciSignedPreKey(), deviceActivationRequest.aciPqLastResortPreKey()),
            userAgent,
            "link-device")
            && PreKeySignatureValidator.validatePreKeySignatures(account.getIdentityKey(IdentityType.PNI),
            List.of(deviceActivationRequest.pniSignedPreKey(), deviceActivationRequest.pniPqLastResortPreKey()),
            userAgent,
            "link-device");

    if (!allKeysValid) {
      throw new WebApplicationException(Response.status(422).build());
    }

    // Normally, the "do we need to refresh somebody's websockets" listener can do this on its own. In this case,
    // we're not using the conventional authentication system, and so we need to give it a hint so it knows who the
    // active user is and what their device states look like.
    LinkedDeviceRefreshRequirementProvider.setAccount(containerRequest, account);

    final int maxDeviceLimit = maxDeviceConfiguration.getOrDefault(account.getNumber(), MAX_DEVICES);

    if (account.getDevices().size() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.getDevices().size(), maxDeviceLimit);
    }

    final DeviceCapabilities capabilities = accountAttributes.getCapabilities();

    if (capabilities == null) {
      throw new WebApplicationException(Response.status(422, "Missing device capabilities").build());
    } else if (isCapabilityDowngrade(account, capabilities)) {
      throw new WebApplicationException(Response.status(409).build());
    }

    final String signalAgent;

    if (deviceActivationRequest.apnToken().isPresent()) {
      signalAgent = "OWP";
    } else if (deviceActivationRequest.gcmToken().isPresent()) {
      signalAgent = "OWA";
    } else {
      signalAgent = "OWD";
    }

    return accounts.addDevice(account, new DeviceSpec(accountAttributes.getName(),
            authorizationHeader.getPassword(),
            signalAgent,
            capabilities,
            accountAttributes.getRegistrationId(),
            accountAttributes.getPhoneNumberIdentityRegistrationId(),
            accountAttributes.getFetchesMessages(),
            deviceActivationRequest.apnToken(),
            deviceActivationRequest.gcmToken(),
            deviceActivationRequest.aciSignedPreKey(),
            deviceActivationRequest.pniSignedPreKey(),
            deviceActivationRequest.aciPqLastResortPreKey(),
            deviceActivationRequest.pniPqLastResortPreKey()))
        .thenCompose(a -> usedTokenCluster.withCluster(connection -> connection.async()
                .set(getUsedTokenKey(linkDeviceRequest.verificationCode()), "", new SetArgs().ex(TOKEN_EXPIRATION_DURATION)))
            .thenApply(ignored -> a))
        .thenApply(accountAndDevice -> new DeviceResponse(
            accountAndDevice.first().getIdentifier(IdentityType.ACI),
            accountAndDevice.first().getIdentifier(IdentityType.PNI),
            accountAndDevice.second().getId()))
        .join();
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/unauthenticated_delivery")
  public void setUnauthenticatedDelivery(@Mutable @Auth AuthenticatedDevice auth) {
    assert (auth.getAuthenticatedDevice() != null);
    // Deprecated
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/capabilities")
  public void setCapabilities(@Mutable @Auth AuthenticatedDevice auth, @NotNull @Valid DeviceCapabilities capabilities) {
    assert (auth.getAuthenticatedDevice() != null);
    final byte deviceId = auth.getAuthenticatedDevice().getId();
    accounts.updateDevice(auth.getAccount(), deviceId, d -> d.setCapabilities(capabilities));
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/public_key")
  @Operation(
      summary = "Sets a public key for authentication",
      description = """
          Sets the authentication public key for the authenticated device. The public key will be used for
          authentication in the nascent gRPC-over-Noise API. Existing devices must upload a public key before they can
          use the gRPC-over-Noise API, and this endpoint exists to facilitate migration to the new API.
          """
  )
  @ApiResponse(responseCode = "200", description = "Public key stored successfully")
  @ApiResponse(responseCode = "401", description = "Account authentication check failed")
  @ApiResponse(responseCode = "422", description = "Invalid request format")
  public CompletableFuture<Void> setPublicKey(@Auth final AuthenticatedDevice auth,
      final SetPublicKeyRequest setPublicKeyRequest) {

    return clientPublicKeysManager.setPublicKey(auth.getAccount(),
        auth.getAuthenticatedDevice().getId(),
        setPublicKeyRequest.publicKey());
  }

  private Mac getInitializedMac() {
    try {
      final Mac mac = Mac.getInstance(VERIFICATION_TOKEN_ALGORITHM);
      mac.init(verificationTokenKey);

      return mac;
    } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
      // All Java implementations must support HmacSHA256 and we checked the key at construction time, so this can never
      // happen
      throw new AssertionError(e);
    }
  }

  @VisibleForTesting
  String generateVerificationToken(final UUID aci) {
    final String claims = aci + "." + clock.instant().toEpochMilli();
    final byte[] signature = getInitializedMac().doFinal(claims.getBytes(StandardCharsets.UTF_8));

    return claims + ":" + Base64.getUrlEncoder().encodeToString(signature);
  }

  @VisibleForTesting
  Optional<UUID> checkVerificationToken(final String verificationToken) {
    final boolean tokenUsed = usedTokenCluster.withCluster(connection ->
        connection.sync().get(getUsedTokenKey(verificationToken)) != null);

    if (tokenUsed) {
      return Optional.empty();
    }

    final String[] claimsAndSignature = verificationToken.split(":", 2);

    if (claimsAndSignature.length != 2) {
      return Optional.empty();
    }

    final byte[] expectedSignature = getInitializedMac().doFinal(
        claimsAndSignature[0].getBytes(StandardCharsets.UTF_8));
    final byte[] providedSignature;

    try {
      providedSignature = Base64.getUrlDecoder().decode(claimsAndSignature[1]);
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }

    if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
      return Optional.empty();
    }

    final String[] aciAndTimestamp = claimsAndSignature[0].split("\\.", 2);

    if (aciAndTimestamp.length != 2) {
      return Optional.empty();
    }

    final UUID aci;

    try {
      aci = UUID.fromString(aciAndTimestamp[0]);
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }

    final Instant timestamp;

    try {
      timestamp = Instant.ofEpochMilli(Long.parseLong(aciAndTimestamp[1]));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }

    final Instant tokenExpiration = timestamp.plus(TOKEN_EXPIRATION_DURATION);

    if (tokenExpiration.isBefore(clock.instant())) {
      return Optional.empty();
    }

    return Optional.of(aci);
  }

  private static boolean isCapabilityDowngrade(Account account, DeviceCapabilities capabilities) {
    boolean isDowngrade = false;
    isDowngrade |= account.isDeleteSyncSupported() && !capabilities.deleteSync();
    isDowngrade |= account.isVersionedExpirationTimerSupported() && !capabilities.versionedExpirationTimer();
    return isDowngrade;
  }

  private static String getUsedTokenKey(final String token) {
    return "usedToken::" + token;
  }
}
