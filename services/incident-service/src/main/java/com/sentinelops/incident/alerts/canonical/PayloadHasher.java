package com.sentinelops.incident.alerts.canonical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex digest of a raw request body — never the body itself is persisted (section 2). */
public final class PayloadHasher {

  private PayloadHasher() {}

  public static String sha256Hex(String rawBody) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawBody.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must always be available", e);
    }
  }
}
