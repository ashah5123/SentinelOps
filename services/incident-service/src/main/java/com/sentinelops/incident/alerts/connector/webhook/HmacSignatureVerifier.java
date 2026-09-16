package com.sentinelops.incident.alerts.connector.webhook;

import com.sentinelops.incident.alerts.AlertsProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 request-signing verification for the generic webhook connector (section 4).
 *
 * <p>The signed material is exactly {@code timestamp + "." + rawRequestBody} — never a
 * re-serialized or parsed form of the body, so a byte-for-byte change anywhere in the request
 * invalidates the signature. Comparison uses {@link MessageDigest#isEqual}, which runs in constant
 * time for equal-length inputs (the standard library's documented constant-time comparison
 * primitive — the same approach used by, e.g., the Stripe and GitHub webhook SDKs).
 *
 * <p>Secret rotation: the caller names which secret it signed with via a key ID header; this class
 * accepts either the {@link AlertsProperties.Hmac#currentSecretId() current} or {@link
 * AlertsProperties.Hmac#previousSecretId() previous} secret, so a secret can be rotated by updating
 * the "current" value and leaving the old one as "previous" until every caller has switched over —
 * no dual-write window is otherwise required.
 *
 * <p>Never logs a signature or secret value anywhere — only the verification outcome and, when
 * relevant, the (non-secret) key ID.
 */
@Component
public class HmacSignatureVerifier {

  public sealed interface Result permits Valid, Invalid {}

  public record Valid(String keyId) implements Result {}

  public record Invalid(String reason) implements Result {}

  private final AlertsProperties.Hmac properties;

  public HmacSignatureVerifier(AlertsProperties properties) {
    this.properties = properties.hmac();
  }

  public Result verify(
      String rawBody, String timestampHeader, String signatureHeader, String keyIdHeader) {
    if (timestampHeader == null || timestampHeader.isBlank()) {
      return new Invalid("missing timestamp header");
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      return new Invalid("missing signature header");
    }
    if (keyIdHeader == null || keyIdHeader.isBlank()) {
      return new Invalid("missing key id header");
    }

    long timestampEpochSeconds;
    try {
      timestampEpochSeconds = Long.parseLong(timestampHeader.trim());
    } catch (NumberFormatException e) {
      return new Invalid("malformed timestamp header");
    }

    Instant timestamp = Instant.ofEpochSecond(timestampEpochSeconds);
    Duration age = Duration.between(timestamp, Instant.now()).abs();
    if (age.compareTo(properties.replayWindow()) > 0) {
      return new Invalid("timestamp outside the allowed replay window");
    }

    String secret = resolveSecret(keyIdHeader);
    if (secret == null) {
      return new Invalid("unknown key id");
    }

    String expectedSignature = sign(secret, timestampHeader, rawBody);
    byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.US_ASCII);
    byte[] providedBytes = signatureHeader.trim().getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
      return new Invalid("signature mismatch");
    }

    return new Valid(keyIdHeader);
  }

  private String resolveSecret(String keyId) {
    if (keyId.equals(properties.currentSecretId())) {
      return properties.currentSecret();
    }
    if (properties.previousSecretId() != null && keyId.equals(properties.previousSecretId())) {
      return properties.previousSecret();
    }
    return null;
  }

  private String sign(String secret, String timestampHeader, String rawBody) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signed =
          mac.doFinal((timestampHeader + "." + rawBody).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signed);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 must always be available", e);
    }
  }
}
