package com.cynthiasystems.agentflow.tasks;

import java.util.concurrent.RejectedExecutionException;

/** Cancellation flag and thread ownership; mutations use the owning task's lifecycle lock. */
final class TaskCancellation {
  private volatile boolean requested;
  private long count;
  private Thread worker;

  boolean requested() {
    return requested;
  }

  long count() {
    return count;
  }

  Throwable processingFailure(final Throwable cause) {
    return requested ? null : cause;
  }

  TaskState completedState() {
    return requested ? TaskState.CANCELLED : TaskState.STOPPED;
  }

  void reset() {
    requested = false;
  }

  void requireAccepting() {
    if (requested) {
      throw new RejectedExecutionException("Task is cancelled; restart it before submitting work");
    }
  }

  void acquire() {
    worker = Thread.currentThread();
  }

  void request() {
    requested = true;
    count++;
    if (worker != null) {
      worker.interrupt();
    }
  }

  // Thread ownership uses identity; null explicitly means the pool thread is no longer ours.
  @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.NullAssignment"})
  void release() {
    if (requested && worker == Thread.currentThread()) {
      Thread.interrupted();
    }
    worker = null;
  }
}
