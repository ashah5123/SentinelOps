package com.sentinelops.incident.alerts.connector.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.AlertsProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class HmacSignatureVerifierTest {

  private static final String CURRENT_SECRET = "current-secret-value";
  private static final String PREVIOUS_SECRET = "previous-secret-value";
  private static final String BODY = "{\"alertName\":\"HighCpu\"}";

  private final AlertsProperties.Hmac properties =
      new AlertsProperties.Hmac(
          "current-key", CURRENT_SECRET, "previous-key", PREVIOUS_SECRET, Duration.ofMinutes(5));
  private final HmacSignatureVerifier verifier = new HmacSignatureVerifier(propsWith(properties));

  @Test
  void aCorrectlySignedRequestWithTheCurrentSecretIsValid() {
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = sign(CURRENT_SECRET, timestamp, BODY);

    var result = verifier.verify(BODY, timestamp, signature, "current-key");

    assertThat(result).isInstanceOf(HmacSignatureVerifier.Valid.class);
  }

  @Test
  void aRequestSignedWithThePreviousSecretDuringRotationIsStillValid() {
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = sign(PREVIOUS_SECRET, timestamp, BODY);

    var result = verifier.verify(BODY, timestamp, signature, "previous-key");

    assertThat(result).isInstanceOf(HmacSignatureVerifier.Valid.class);
  }

  @Test
  void anUnknownKeyIdIsRejected() {
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = sign(CURRENT_SECRET, timestamp, BODY);

    var result = verifier.verify(BODY, timestamp, signature, "some-other-key");

    assertThat(result).isInstanceOf(HmacSignatureVerifier.Invalid.class);
  }

  @Test
  void aTimestampOutsideTheReplayWindowIsRejected() {
    String oldTimestamp =
        String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
    String signature = sign(CURRENT_SECRET, oldTimestamp, BODY);

    var result = verifier.verify(BODY, oldTimestamp, signature, "current-key");

    assertThat(result).isInstanceOf(HmacSignatureVerifier.Invalid.class);
    assertThat(((HmacSignatureVerifier.Invalid) result).reason()).contains("replay window");
  }

  @Test
  void aTamperedBodyInvalidatesTheSignature() {
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = sign(CURRENT_SECRET, timestamp, BODY);

    var result = verifier.verify(BODY + "tampered", timestamp, signature, "current-key");

    assertThat(result).isInstanceOf(HmacSignatureVerifier.Invalid.class);
  }

  @Test
  void missingHeadersAreRejected() {
    assertThat(verifier.verify(BODY, null, "sig", "current-key"))
        .isInstanceOf(HmacSignatureVerifier.Invalid.class);
    assertThat(verifier.verify(BODY, "123", null, "current-key"))
        .isInstanceOf(HmacSignatureVerifier.Invalid.class);
    assertThat(verifier.verify(BODY, "123", "sig", null))
        .isInstanceOf(HmacSignatureVerifier.Invalid.class);
  }

  @Test
  void aMalformedTimestampIsRejected() {
    var result = verifier.verify(BODY, "not-a-number", "sig", "current-key");
    assertThat(result).isInstanceOf(HmacSignatureVerifier.Invalid.class);
  }

  @Test
  void wrongSecretProducesAnInvalidSignature() {
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = sign("wrong-secret", timestamp, BODY);

    var result = verifier.verify(BODY, timestamp, signature, "current-key");

    assertThat(result).isInstanceOf(HmacSignatureVerifier.Invalid.class);
  }

  private String sign(String secret, String timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signed = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signed);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException(e);
    }
  }

  private AlertsProperties propsWith(AlertsProperties.Hmac hmac) {
    var base = com.sentinelops.incident.alerts.AlertsPropertiesFixtures.minimal();
    return new AlertsProperties(
        base.ingestion(),
        hmac,
        base.alertmanager(),
        base.rateLimit(),
        base.correlation(),
        base.notification(),
        base.escalation(),
        base.routing());
  }
}
