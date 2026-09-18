package com.sentinelops.incident.slo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PrometheusClientTest {

  private final PrometheusClient client = new PrometheusClient(new ObjectMapper(), "http://unused");

  @Test
  void parsesASuccessfulInstantVectorResponse() {
    String body =
        """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{},"value":[1700000000,"0.997"]}
        ]}}
        """;

    var value = client.parseInstantQueryResponse(body);

    assertThat(value).isPresent();
    assertThat(value.get()).isEqualTo(0.997);
  }

  @Test
  void returnsEmptyForAnEmptyResultVector() {
    String body =
        """
        {"status":"success","data":{"resultType":"vector","result":[]}}
        """;

    assertThat(client.parseInstantQueryResponse(body)).isEmpty();
  }

  @Test
  void returnsEmptyForAnErrorStatus() {
    String body =
        """
        {"status":"error","errorType":"bad_data","error":"parse error"}
        """;

    assertThat(client.parseInstantQueryResponse(body)).isEmpty();
  }

  @Test
  void returnsEmptyForMalformedJson() {
    assertThat(client.parseInstantQueryResponse("not json at all")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheValueArrayIsMalformed() {
    String body =
        """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{},"value":[1700000000]}
        ]}}
        """;

    assertThat(client.parseInstantQueryResponse(body)).isEmpty();
  }
}
