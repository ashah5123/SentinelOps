package com.sentinelops.incident.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.AiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Calls a local Ollama instance's embeddings endpoint. Requires the configured embedding model (see
 * {@code sentinelops.ai.ollama.embedding-model}) to produce exactly {@value
 * DeterministicEmbeddingProvider#DIMENSIONS}-dimension vectors — this is a real constraint of the
 * fixed {@code vector(384)} column, not an arbitrary limitation; pick a small local embedding model
 * with that output size (see docs/development/ai-triage.md for the specific model this repository
 * was validated against). Never used directly when Ollama is unreachable — see {@code
 * AiTriageService}'s fallback-to-unavailable handling, which this class participates in by throwing
 * {@link EmbeddingUnavailableException} rather than a raw I/O exception.
 */
public class OllamaEmbeddingProvider implements EmbeddingProvider {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final AiProperties properties;

  public OllamaEmbeddingProvider(AiProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(properties.ollama().timeout()).build();
  }

  @Override
  public float[] embed(String text) {
    try {
      String requestBody =
          objectMapper.writeValueAsString(
              Map.of("model", properties.ollama().embeddingModel(), "prompt", text));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.ollama().baseUrl() + "/api/embeddings"))
              .timeout(properties.ollama().timeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new EmbeddingUnavailableException(
            "Ollama embeddings endpoint returned status " + response.statusCode());
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
      @SuppressWarnings("unchecked")
      var rawEmbedding = (java.util.List<Number>) body.get("embedding");
      if (rawEmbedding == null || rawEmbedding.size() != dimensions()) {
        throw new EmbeddingUnavailableException(
            "Configured Ollama embedding model returned "
                + (rawEmbedding == null ? "no" : rawEmbedding.size())
                + " dimensions; expected "
                + dimensions()
                + ". Configure sentinelops.ai.ollama.embedding-model to a model with this output"
                + " size.");
      }
      float[] vector = new float[dimensions()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = rawEmbedding.get(i).floatValue();
      }
      return vector;
    } catch (EmbeddingUnavailableException e) {
      throw e;
    } catch (Exception e) {
      throw new EmbeddingUnavailableException("Could not reach Ollama for embeddings", e);
    }
  }

  @Override
  public int dimensions() {
    return DeterministicEmbeddingProvider.DIMENSIONS;
  }

  @Override
  public String name() {
    return "ollama:" + properties.ollama().embeddingModel();
  }
}
