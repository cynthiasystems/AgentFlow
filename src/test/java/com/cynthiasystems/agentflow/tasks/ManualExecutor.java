package com.cynthiasystems.agentflow.tasks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Single-threaded test driver: submissions run only when the test explicitly advances it. */
public class ManualExecutor extends AbstractExecutorService {
  private final Deque<Runnable> work = new ArrayDeque<>();
  private boolean shutdown;

  public int pending() {
    return work.size();
  }

  public void runNext() {
    work.removeFirst().run();
  }

  @Override
  public void execute(final Runnable command) {
    if (shutdown) {
      throw new RejectedExecutionException("Executor stopped");
    }
    work.addLast(command);
  }

  @Override
  public void shutdown() {
    shutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown();
    final List<Runnable> remaining = new ArrayList<>(work);
    work.clear();
    return remaining;
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown && work.isEmpty();
  }

  @Override
  public boolean awaitTermination(final long timeout, final TimeUnit unit) {
    return isTerminated();
  }
}
