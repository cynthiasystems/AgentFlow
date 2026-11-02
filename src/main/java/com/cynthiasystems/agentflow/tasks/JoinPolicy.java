package com.cynthiasystems.agentflow.tasks;

import java.time.Duration;

/**
 * Memory bounds and the deadline measured from processing a request's first input.
 *
 * @param timeout positive time allowed to collect all branches
 * @param maxPendingRequests maximum number of incomplete requests
 * @param rememberedRequests maximum number of finished IDs retained for duplicate suppression
 * @param mailboxCapacity maximum number of waiting input messages
 */
public record JoinPolicy(
    Duration timeout, int maxPendingRequests, int rememberedRequests, int mailboxCapacity) {
  public JoinPolicy {
    if (timeout.toNanos() <= 0) {
      throw new IllegalArgumentException("Join timeout must be positive and fit in nanoseconds");
    }
    if (maxPendingRequests <= 0 || rememberedRequests <= 0 || mailboxCapacity <= 0) {
      throw new IllegalArgumentException("Join capacities must be positive");
    }
  }

  public static JoinPolicy defaults() {
    return new JoinPolicy(Duration.ofSeconds(30), 1024, 4096, Task.DEFAULT_MAILBOX_CAPACITY);
  }
}
