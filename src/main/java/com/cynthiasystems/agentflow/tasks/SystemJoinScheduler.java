package com.cynthiasystems.agentflow.tasks;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One daemon timer shared by joins; callbacks only signal tasks, never run join logic. */
final class SystemJoinScheduler implements JoinScheduler {
  static final SystemJoinScheduler INSTANCE = new SystemJoinScheduler();
  private final ScheduledThreadPoolExecutor timer;

  private SystemJoinScheduler() {
    timer =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              final Thread thread = new Thread(runnable, "agentflow-join-timer");
              thread.setDaemon(true);
              return thread;
            });
    timer.setRemoveOnCancelPolicy(true);
  }

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  @Override
  public Cancellation schedule(final Runnable notification, final long delayNanos) {
    final ScheduledFuture<?> future =
        timer.schedule(notification, delayNanos, TimeUnit.NANOSECONDS);
    return () -> future.cancel(false);
  }
}
