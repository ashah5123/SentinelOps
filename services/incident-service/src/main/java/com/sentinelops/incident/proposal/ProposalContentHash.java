package com.sentinelops.incident.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A SHA-256 digest over the exact fields that must never change between proposal and execution
 * (section 8/10: "reject altered parameters"). Parameter keys are sorted before hashing so
 * key-insertion order (which JSON round-tripping does not guarantee to preserve) never changes the
 * hash for logically identical parameters.
 */
public final class ProposalContentHash {

  private ProposalContentHash() {}

  public static String compute(
      ObjectMapper objectMapper,
      UUID incidentId,
      ProposalActionType actionType,
      Map<String, Object> parameters,
      long expectedVersion) {
    try {
      String canonicalParams = objectMapper.writeValueAsString(new TreeMap<>(parameters));
      String raw = incidentId + "|" + actionType + "|" + canonicalParams + "|" + expectedVersion;
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Failed to compute proposal content hash", e);
    }
  }
}
