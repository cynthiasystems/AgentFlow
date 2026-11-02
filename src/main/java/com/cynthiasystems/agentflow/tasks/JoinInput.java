package com.cynthiasystems.agentflow.tasks;

import java.util.Objects;

/**
 * A publication envelope attached automatically by a subscribed publisher.
 *
 * @param <K> request ID type
 * @param <V> value type
 * @param requestId correlation ID shared by all publishers for one request
 * @param publisherId unique instance ID of the publishing task
 * @param publisherName name of the publishing task, used as the join input key
 * @param value non-null published value
 */
public record JoinInput<K, V>(K requestId, String publisherId, String publisherName, V value) {
  public JoinInput {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(publisherId, "publisherId");
    Objects.requireNonNull(publisherName, "publisherName");
    Objects.requireNonNull(value, "value");
    if (publisherName.isBlank()) {
      throw new IllegalArgumentException("Publisher name must not be blank");
    }
  }
}
