package com.cynthiasystems.agentflow.tasks;

import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Synchronized;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

@Accessors(fluent = true, chain = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public abstract class AutonomousTask implements Runnable {
  @Getter(AccessLevel.PUBLIC)
  String id = UUID.randomUUID().toString();

  @Getter(AccessLevel.PUBLIC)
  @NonFinal
  private volatile TaskState state = TaskState.CREATED;

  @NonFinal Thread thread;

  /** Starts the agent in its own thread. */
  @Synchronized
  public void start() {
    if (state == TaskState.STARTED) {
      return;
    }
    beforeStart();
    thread = new Thread(this, id());
    thread.start();
  }

  /** Stops the agent gracefully. */
  @Synchronized
  public void stop() {
    state = TaskState.STOPPED;
    if (thread != null) {
      thread.interrupt();
    }
    afterStop();
  }

  /**
   * @return true if this task's thread is alive
   */
  public boolean isThreadAlive() {
    return thread != null && thread.isAlive();
  }

  /**
   * @return thread name or null if not started
   */
  public String getThreadName() {
    return thread != null ? thread.getName() : null;
  }

  @Override
  public final void run() {
    try {
      if (state != TaskState.STARTED) {
        throw new IllegalStateException("Task must be started using start() method");
      }
      initialize();
      while (state == TaskState.STARTED) {
        if (shouldProcess()) {
          process();
        }
        try {
          Thread.sleep(calculateSleepTime());
        } catch (final InterruptedException e) {
          thread.interrupt();
        }
      }
    } finally {
      cleanup();
    }
  }

  /** Lifecycle hook called before the agent is started. */
  protected void beforeStart() {}

  /** Lifecycle hook called after the agent is stopped. */
  protected void afterStop() {}

  /** Lifecycle hook for initialization when the agent thread starts. */
  protected void initialize() {}

  /** Lifecycle hook for cleanup when the agent thread terminates. */
  protected void cleanup() {}

  /**
   * Determines whether the agent should process in the current cycle.
   *
   * @return true if processing should occur
   */
  protected abstract boolean shouldProcess();

  /** Performs the agent's core processing logic. */
  protected abstract void process();

  /**
   * Calculates appropriate sleep time based on agent-specific strategy.
   *
   * @return sleep time in milliseconds
   */
  protected abstract long calculateSleepTime();
}
