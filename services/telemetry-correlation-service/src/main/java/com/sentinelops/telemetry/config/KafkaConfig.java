package com.sentinelops.telemetry.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sentinelops.telemetry.events.EventTypes;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Kafka-compatible client configuration. Both keys and values are transported as plain strings —
 * this service explicitly controls JSON serialization of the {@code EventEnvelope} itself (see
 * {@code OutboxWriter} and the Kafka listeners) rather than Spring Kafka's type-mapping JSON
 * (de)serializers, so published messages carry no Java-class type headers. Mirrors the incident
 * service's own {@code KafkaConfig}, including at-least-once delivery with bounded exponential
 * backoff plus jitter, non-retryable-exception fast paths, and dead-letter routing (see ADR 0008).
 */
@Configuration
public class KafkaConfig {

  @SuppressWarnings("unchecked")
  private static final Class<? extends Exception>[] NON_RETRYABLE_EXCEPTIONS =
      new Class[] {JsonProcessingException.class, IllegalArgumentException.class};

  private final KafkaProperties kafkaProperties;

  public KafkaConfig(KafkaProperties kafkaProperties) {
    this.kafkaProperties = kafkaProperties;
  }

  @Bean
  public ProducerFactory<String, String> producerFactory() {
    Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  @Bean
  public ConsumerFactory<String, String> consumerFactory() {
    Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate,
      TelemetryCorrelationProperties properties,
      TelemetryMetrics metrics) {
    TelemetryCorrelationProperties.Consumer consumerProperties = properties.consumer();
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setDeliveryAttemptHeader(true);

    DeadLetterPublishingRecoverer deadLetterRecoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                switch (record.topic()) {
                  case EventTypes.DEPLOYMENT_CHANGED_V1 ->
                      new TopicPartition(EventTypes.DEPLOYMENT_CHANGED_V1_DLQ, -1);
                  case EventTypes.SERVICE_DEPENDENCY_CHANGED_V1 ->
                      new TopicPartition(EventTypes.SERVICE_DEPENDENCY_CHANGED_V1_DLQ, -1);
                  default -> new TopicPartition(record.topic() + ".dlq", -1);
                });
    ConsumerRecordRecoverer recoverer =
        (record, exception) -> {
          metrics.consumerDeadLettered(record.topic());
          deadLetterRecoverer.accept(record, exception);
        };

    JitteredExponentialBackOff backOff =
        new JitteredExponentialBackOff(
            consumerProperties.retryInitialInterval().toMillis(),
            consumerProperties.retryMultiplier(),
            consumerProperties.retryMaxInterval().toMillis(),
            consumerProperties.retryMaxInterval().toMillis() * consumerProperties.maxRetries(),
            consumerProperties.retryJitter());

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
    errorHandler.addNotRetryableExceptions(NON_RETRYABLE_EXCEPTIONS);
    errorHandler.setRetryListeners(
        (record, ex, deliveryAttempt) -> metrics.consumerRetryAttempted(record.topic()));
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
