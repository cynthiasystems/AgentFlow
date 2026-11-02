package com.cynthiasystems.agentflow.tasks;

/** Monotonic clock and one-shot notifications, replaceable by a controlled clock in tests. */
interface JoinScheduler {
  long nanoTime();

  Cancellation schedule(Runnable notification, long delayNanos);

  interface Cancellation {
    void cancel();
  }
}
