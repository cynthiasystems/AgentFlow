package com.cynthiasystems.agentflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import com.cynthiasystems.agentflow.tasks.Task;

import lombok.Builder;
import lombok.Singular;

/** Owns a shared worker pool for a group of message-driven tasks. */
public final class Agent {
  private final List<Task<?>> tasks;
  private final int parallelism;
  private final IntFunction<ExecutorService> executorFactory;
  private ExecutorService executor;

  public static AgentBuilder builder() {
    return new AgentBuilder();
  }

  @Builder
  private Agent(@Singular final List<Task<?>> tasks, final int parallelism) {
    this(tasks, parallelism, Agent::newExecutor);
  }

  Agent(
      final List<Task<?>> tasks,
      final int parallelism,
      final IntFunction<ExecutorService> executorFactory) {
    if (parallelism < 0) {
      throw new IllegalArgumentException("Parallelism must not be negative");
    }
    if (new HashSet<>(tasks).size() != tasks.size()) {
      throw new IllegalArgumentException("A task may only appear once in an agent");
    }
    this.tasks = List.copyOf(tasks);
    this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    this.parallelism =
        parallelism == 0
            ? Math.max(1, Math.min(tasks.size(), Runtime.getRuntime().availableProcessors()))
            : parallelism;
  }

  public synchronized void start() {
    if (executor != null && !executor.isShutdown()) {
      return;
    }
    if (executor != null && !executor.isTerminated()) {
      throw new IllegalStateException("Wait for the agent to stop before restarting");
    }
    Task.freezeGraph(tasks);
    executor = executorFactory.apply(parallelism);
    final List<Task<?>> started = new ArrayList<>();
    try {
      for (final Task<?> task : tasks) {
        task.start(executor);
        started.add(task);
      }
    } catch (final RuntimeException e) {
      for (final Task<?> task : started) {
        task.stop();
      }
      executor.shutdown();
      throw e;
    }
  }

  private static ExecutorService newExecutor(final int parallelism) {
    final ThreadFactory factory = Executors.defaultThreadFactory();
    return Executors.newFixedThreadPool(
        parallelism,
        runnable -> {
          final Thread thread = factory.newThread(runnable);
          thread.setName("agentflow-" + thread.getName());
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Requests every task to stop, then closes the pool. Does not wait or discard queued messages.
   */
  public synchronized void stop() {
    for (final Task<?> task : tasks) {
      task.stop();
    }
    if (executor != null) {
      executor.shutdown();
    }
  }

  /** Waits up to the supplied timeout after stop. Do not call from an agent's worker. */
  // Wait on a snapshot of our own executor without holding the agent's lifecycle monitor.
  @SuppressWarnings("PMD.LawOfDemeter")
  public boolean awaitStopped(final Duration timeout) throws InterruptedException {
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("Timeout must not be negative");
    }
    final ExecutorService current;
    synchronized (this) {
      current = executor;
    }
    final long budget = timeout.toNanos();
    final long started = System.nanoTime();
    for (final Task<?> task : tasks) {
      final long remaining = Math.max(0, budget - (System.nanoTime() - started));
      if (!task.awaitStopped(Duration.ofNanos(remaining))) {
        return false;
      }
    }
    final long remaining = Math.max(0, budget - (System.nanoTime() - started));
    return current == null || current.awaitTermination(remaining, TimeUnit.NANOSECONDS);
  }
}
