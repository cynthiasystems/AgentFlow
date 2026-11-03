package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class AdvisoryTest {
  private static final Duration WAIT = Duration.ofSeconds(5);

  @Test
  public void optionalMessagesDoNotBecomeJoinDependenciesOrTriggerPrematureComputation() {
    final ManualExecutor executor = new ManualExecutor();
    final List<Object> hints = new ArrayList<>();
    final List<Integer> results = new ArrayList<>();
    final JoinTask<Integer, Integer, Integer> join =
        new JoinTask<>(
            "join",
            JoinPolicy.defaults(),
            (id, inputs) -> {
              results.add(inputs.get("a") + inputs.get("b"));
              return 0;
            },
            failure -> {}) {
          @Override
          protected void onAdvisory(final Object hint) {
            hints.add(hint);
          }
        };
    final RelayTask<Integer, Integer> a = RelayTask.of("a", x -> x);
    final RelayTask<Integer, Integer> b = RelayTask.of("b", x -> x);
    final AdviceSource source = new AdviceSource();
    a.relay(join, x -> 1);
    b.relay(join, x -> 1);
    source.relayAdvisories(join);
    assertEquals(Set.of("a", "b"), join.publisherNames());
    assertThrows(IllegalArgumentException.class, () -> source.relayAdvisories(join));
    assertThrows(NullPointerException.class, () -> source.relayAdvisories(null));
    assertThrows(NullPointerException.class, () -> source.broadcast(null));
    final List<Task<?>> tasks = List.of(source, a, b, join);
    for (final Task<?> task : tasks) {
      task.start(executor);
    }
    source.accept("prefer cached data");
    a.accept(3);
    drain(executor);
    assertEquals(List.of("prefer cached data"), hints);
    assertTrue(results.isEmpty());
    b.accept(4);
    drain(executor);
    assertEquals(List.of(7), results);
    source.accept(TaskCommand.STOP);
    drain(executor);
    assertEquals(TaskState.STOPPED, join.state());
    for (final Task<?> task : tasks) {
      task.stop();
    }
    drain(executor);
  }

  @Test
  public void stopHasReservedPriorityAndOptionalAdviceDoesNotStarveData() {
    final ManualExecutor executor = new ManualExecutor();
    final List<String> order = new ArrayList<>();
    final Task<Integer> task =
        new Task<>(1) {
          @Override
          protected void process(final Integer value) {
            order.add("data");
          }

          @Override
          protected void onAdvisory(final Object message) {
            order.add("advice");
          }
        };
    task.accept(1);
    for (int i = 0; i < TaskInbox.ADVISORY_CAPACITY; i++) {
      task.advise("hint");
    }
    assertThrows(RejectedExecutionException.class, () -> task.advise("overflow"));
    task.advise(TaskCommand.STOP);
    task.advise(TaskCommand.STOP);
    assertEquals(65, task.advisoryInboxSize());
    task.start(executor);
    drain(executor);
    assertEquals(TaskState.STOPPED, task.state());
    assertTrue(order.isEmpty());
    assertEquals(1, task.inboxSize());
    assertEquals(64, task.advisoryInboxSize());
    task.start(executor);
    drain(executor);
    assertEquals(List.of("advice", "data"), order.subList(0, 2));
    assertEquals(65, order.size());
    assertEquals(0, task.advisoryInboxSize());
    task.advise("wake idle");
    drain(executor);
    assertEquals(66, order.size());
    task.stop();
    drain(executor);
    assertThrows(NullPointerException.class, () -> task.advise(null));
  }

  @Test
  public void stopArrivingAtBatchBoundaryIsRescheduledEvenWithEmptyDataQueue() {
    final ManualExecutor executor = new ManualExecutor();
    final AtomicInteger processed = new AtomicInteger();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer value) {
            if (processed.incrementAndGet() == 64) {
              advise(TaskCommand.STOP);
            }
          }
        };
    for (int i = 0; i < 64; i++) {
      task.accept(i);
    }
    task.start(executor);
    executor.runNext();
    assertEquals(0, task.inboxSize());
    assertEquals(1, task.advisoryInboxSize());
    assertEquals(1, executor.pending());
    drain(executor);
    assertEquals(TaskState.STOPPED, task.state());
    assertEquals(64, processed.get());
  }

  @Test
  public void defaultAdviceIsOptionalAndNotificationsShareTheControlTurnFairly() {
    final ManualExecutor executor = new ManualExecutor();
    final AtomicInteger data = new AtomicInteger();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer value) {
            data.addAndGet(value);
          }
        };
    task.advise("ignored by default");
    task.signal();
    task.accept(1);
    task.start(executor);
    drain(executor);
    assertEquals(1, data.get());
    assertEquals(0, task.advisoryInboxSize());
    task.stop();
    drain(executor);
  }

  @Test
  public void killBeforeStartAndWhileIdleClearsBothQueuesAndAllowsExplicitRestart() {
    final ManualExecutor executor = new ManualExecutor();
    final AtomicInteger calls = new AtomicInteger();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer value) {
            calls.incrementAndGet();
          }
        };
    task.accept(1);
    task.advise("hint");
    task.signal();
    task.advise(TaskCommand.KILL);
    task.advise(TaskCommand.KILL);
    assertEquals(TaskState.CANCELLED, task.state());
    assertEquals(0, task.inboxSize());
    assertEquals(0, task.advisoryInboxSize());
    assertThrows(RejectedExecutionException.class, () -> task.accept(2));
    assertThrows(RejectedExecutionException.class, () -> task.advise("late"));
    task.start(executor);
    drain(executor);
    task.accept(3);
    drain(executor);
    assertEquals(1, calls.get());
    task.advise(TaskCommand.KILL);
    drain(executor);
    assertEquals(TaskState.CANCELLED, task.state());
    assertNull(task.failure());
  }

  @Test
  public void killOfQueuedWorkerSkipsInitializationAndCleanupFailureRemainsVisible() {
    final ManualExecutor executor = new ManualExecutor();
    final AtomicInteger initialized = new AtomicInteger();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void initialize() {
            initialized.incrementAndGet();
          }

          @Override
          protected void process(final Integer value) {}

          @Override
          protected void cleanup() {
            throw new IllegalStateException("cleanup failed");
          }
        };
    task.start(executor);
    task.advise(TaskCommand.KILL);
    drain(executor);
    assertEquals(0, initialized.get());
    assertEquals(TaskState.FAILED, task.state());
    assertEquals("cleanup failed", task.failure().getMessage());
  }

  @Test
  public void killStillCompletesWhenIdleExecutorHasAlreadyBeenClosed() {
    final ManualExecutor executor = new ManualExecutor();
    final Task<Integer> task = RelayTask.of(x -> x);
    task.start(executor);
    drain(executor);
    executor.shutdown();
    task.advise(TaskCommand.KILL);
    assertEquals(TaskState.CANCELLED, task.state());
    assertNull(task.failure());
  }

  @Test
  public void killInterruptsActiveWorkAndDoesNotLeakInterruptToTheNextTask() throws Exception {
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    final Task<Integer> blocked =
        new Task<>() {
          @Override
          protected void process(final Integer value) throws InterruptedException {
            calls.incrementAndGet();
            entered.countDown();
            new CountDownLatch(1).await();
          }
        };
    try {
      blocked.accept(1);
      blocked.accept(2);
      blocked.start(pool);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      blocked.stop();
      blocked.advise(TaskCommand.KILL);
      assertTrue(blocked.awaitStopped(WAIT));
      assertEquals(TaskState.CANCELLED, blocked.state());
      assertEquals(1, calls.get());
      assertEquals(0, blocked.inboxSize());
      assertNull(blocked.failure());
      assertFalse(
          pool.submit(() -> Thread.currentThread().isInterrupted()).get(5, TimeUnit.SECONDS));
    } finally {
      blocked.advise(TaskCommand.KILL);
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void killCannotForceCodeThatIgnoresInterruptsToExit() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Task<Integer> stubborn =
        new Task<>() {
          @Override
          protected void process(final Integer value) {
            entered.countDown();
            boolean done = false;
            while (!done) {
              try {
                release.await();
                done = true;
              } catch (InterruptedException expected) {
                interrupted.countDown();
              }
            }
          }
        };
    try {
      stubborn.accept(1);
      stubborn.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      stubborn.advise(TaskCommand.KILL);
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertFalse(stubborn.awaitStopped(Duration.ZERO));
      assertEquals(TaskState.STOPPING, stubborn.state());
    } finally {
      release.countDown();
      assertTrue(stubborn.awaitStopped(WAIT));
    }
    assertEquals(TaskState.CANCELLED, stubborn.state());
  }

  @Test
  public void advisoryHandlerFailureUsesTheNormalFailureLifecycle() {
    final ManualExecutor executor = new ManualExecutor();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer value) {}

          @Override
          protected void onAdvisory(final Object message) {
            throw new IllegalArgumentException("bad hint");
          }
        };
    task.start(executor);
    drain(executor);
    task.advise("bad");
    drain(executor);
    assertEquals(TaskState.FAILED, task.state());
    assertEquals("bad hint", task.failure().getMessage());
  }

  @Test
  public void conditionalPublicationCanStopFeedbackWithoutChangingRequiredPublishers() {
    final ManualExecutor executor = new ManualExecutor();
    final List<Integer> results = new ArrayList<>();
    final JoinTask<Integer, Integer, Integer> join =
        new JoinTask<>(
            "join",
            JoinPolicy.defaults(),
            (id, inputs) -> {
              results.add(id);
              return 0;
            },
            failure -> {});
    final RelayTask<Integer, Integer> source = RelayTask.of("source", x -> x);
    assertThrows(NullPointerException.class, () -> source.relay(join, x -> x, null));
    source.relay(join, x -> x, x -> x % 2 == 0);
    source.accept(1);
    source.accept(2);
    source.start(executor);
    join.start(executor);
    drain(executor);
    assertEquals(List.of(2), results);
    assertEquals(Set.of("source"), join.publisherNames());
    source.stop();
    join.stop();
    drain(executor);
  }

  @Test
  public void killDiscardsPartialJoinsAndCancelsTheirTimers() {
    checkPartialCancellation(false);
    checkPartialCancellation(true);
  }

  private static void checkPartialCancellation(final boolean stopFirst) {
    final ManualExecutor executor = new ManualExecutor();
    final ManualJoinScheduler clock = new ManualJoinScheduler();
    final List<Integer> results = new ArrayList<>();
    final JoinTask<Integer, Integer, Integer> join =
        new JoinTask<>(
            "join",
            JoinPolicy.defaults(),
            (id, inputs) -> {
              results.add(inputs.get("a") + inputs.get("b"));
              return 0;
            },
            failure -> {},
            clock);
    final RelayTask<Integer, Integer> a = RelayTask.of("a", x -> x);
    final RelayTask<Integer, Integer> b = RelayTask.of("b", x -> x);
    a.relay(join, x -> 1);
    b.relay(join, x -> 1);
    a.start(executor);
    b.start(executor);
    join.start(executor);
    drain(executor);
    a.accept(3);
    drain(executor);
    assertEquals(1, clock.activeAlarms());
    if (stopFirst) {
      join.stop();
      drain(executor);
    }
    join.advise(TaskCommand.KILL);
    drain(executor);
    assertEquals(0, clock.activeAlarms());
    join.start(executor);
    drain(executor);
    b.accept(4);
    drain(executor);
    assertTrue(results.isEmpty());
    a.accept(8);
    drain(executor);
    assertEquals(List.of(12), results);
    a.stop();
    b.stop();
    join.stop();
    drain(executor);
  }

  private static void drain(final ManualExecutor executor) {
    while (executor.pending() > 0) {
      executor.runNext();
    }
  }

  private static final class AdviceSource extends PublishingTask<Object, Void> {
    private AdviceSource() {
      super("advice", 64);
    }

    @Override
    protected void process(final Object message) {
      publishAdvisory(message);
    }

    private void broadcast(final Object message) {
      publishAdvisory(message);
    }
  }
}
