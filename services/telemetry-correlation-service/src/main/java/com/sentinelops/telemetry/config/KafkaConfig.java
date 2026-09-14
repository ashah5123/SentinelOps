package com.sentinelops.telemetry.config;

import com.sentinelops.telemetry.events.EventTypes;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka-compatible client configuration. Both keys and values are transported as plain strings —
 * this service explicitly controls JSON serialization of the {@code EventEnvelope} itself (see
 * {@code OutboxWriter} and the Kafka listeners) rather than Spring Kafka's type-mapping JSON
 * (de)serializers, so published messages carry no Java-class type headers. Mirrors the incident
 * service's own {@code KafkaConfig}, including at-least-once delivery with bounded exponential
 * backoff and dead-letter routing (see ADR 0008).
 */
@Configuration
public class KafkaConfig {

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
      @Value("${sentinelops.telemetry.consumer.max-retries}") int maxRetries) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

    DeadLetterPublishingRecoverer recoverer =
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

    // Bounded exponential backoff: 1s, 2s, 4s, ... capped at 30s, for maxRetries attempts
    // before the record is published to its dead-letter topic.
    ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxInterval(30_000L);
    backOff.setMaxElapsedTime(30_000L * maxRetries);

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
