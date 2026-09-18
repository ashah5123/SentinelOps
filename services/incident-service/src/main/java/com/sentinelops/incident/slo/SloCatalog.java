package com.sentinelops.incident.slo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Loads the version-controlled SLO catalog from {@code classpath:slo/slo-definitions.yaml}. */
@Component
public final class SloCatalog {

  private final List<SloDefinition> definitions;

  public SloCatalog() {
    this("slo/slo-definitions.yaml");
  }

  SloCatalog(String classpathResource) {
    this.definitions = load(classpathResource);
  }

  public List<SloDefinition> all() {
    return definitions;
  }

  @SuppressWarnings("unchecked")
  private List<SloDefinition> load(String classpathResource) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    try (InputStream stream = new ClassPathResource(classpathResource).getInputStream()) {
      Map<String, Object> root = yaml.load(stream);
      List<Map<String, Object>> rawSlos = (List<Map<String, Object>>) root.get("slos");
      return rawSlos.stream()
          .map(
              raw ->
                  new SloDefinition(
                      (String) raw.get("id"),
                      (String) raw.get("name"),
                      (String) raw.get("description"),
                      (String) raw.get("sliQuery"),
                      ((Number) raw.get("objective")).doubleValue(),
                      ((Number) raw.get("windowDays")).intValue()))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load SLO catalog from " + classpathResource, e);
    }
  }
}
