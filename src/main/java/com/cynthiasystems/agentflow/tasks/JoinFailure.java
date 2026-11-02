package com.cynthiasystems.agentflow.tasks;

import java.util.Objects;
import java.util.Set;

/**
 * A request that could not be joined. This outcome does not itself fail the task.
 *
 * @param <K> request ID type
 * @param requestId correlation ID of the rejected or expired request
 * @param reason why the join could not finish
 * @param missingPublishers publishers with no retained result; all required publishers on capacity
 *     rejection
 */
public record JoinFailure<K>(K requestId, Reason reason, Set<String> missingPublishers) {
  public enum Reason {
    TIMEOUT,
    CAPACITY
  }

  public JoinFailure {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(reason, "reason");
    missingPublishers = Set.copyOf(missingPublishers);
  }
}
