package com.cynthiasystems.agentflow.tasks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Data and advisory queues. All access is guarded by the owning task's lifecycle lock.
 *
 * @param <X> data message type
 */
final class TaskInbox<X> {
  static final int ADVISORY_CAPACITY = 64;
  private final Deque<X> data = new ArrayDeque<>();
  private final Deque<Object> advisories = new ArrayDeque<>();
  private final int capacity;
  private final Handler<X> process;
  private final Consumer<Object> advisory;
  private final Action notification;
  private final Action stop;
  private boolean signaled;
  private boolean stopAdvised;
  private boolean controlTurn = true;

  TaskInbox(
      final int capacity,
      final Handler<X> process,
      final Consumer<Object> advisory,
      final Action notification,
      final Action stop) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Mailbox capacity must be positive");
    }
    this.capacity = capacity;
    this.process = process;
    this.advisory = advisory;
    this.notification = notification;
    this.stop = stop;
  }

  void add(final X value) {
    if (data.size() == capacity) {
      throw new RejectedExecutionException("Task data inbox is full");
    }
    data.addLast(value);
  }

  void advise(final Object message) {
    if (message == TaskCommand.STOP) {
      stopAdvised = true;
    } else {
      if (advisories.size() == ADVISORY_CAPACITY) {
        throw new RejectedExecutionException("Task advisory inbox is full");
      }
      advisories.addLast(message);
    }
  }

  void signal() {
    signaled = true;
  }

  boolean isEmpty() {
    return data.isEmpty() && advisories.isEmpty() && !signaled && !stopAdvised;
  }

  int dataSize() {
    return data.size();
  }

  int advisorySize() {
    return advisories.size() + (stopAdvised ? 1 : 0);
  }

  Action next() {
    if (stopAdvised) {
      stopAdvised = false;
      return stop;
    }
    if ((signaled || !advisories.isEmpty()) && (controlTurn || data.isEmpty())) {
      controlTurn = false;
      if (signaled) {
        signaled = false;
        return notification;
      }
      final Object message = advisories.removeFirst();
      return () -> advisory.accept(message);
    }
    if (!data.isEmpty()) {
      controlTurn = true;
      final X message = data.removeFirst();
      return () -> process.accept(message);
    }
    return null;
  }

  void clear() {
    data.clear();
    advisories.clear();
    signaled = false;
    stopAdvised = false;
    controlTurn = true;
  }

  @FunctionalInterface
  interface Action {
    void run() throws Exception;
  }

  @FunctionalInterface
  interface Handler<X> {
    void accept(X value) throws Exception;
  }
}
