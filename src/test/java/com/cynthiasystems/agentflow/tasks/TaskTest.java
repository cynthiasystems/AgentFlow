package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class TaskTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  public void idleTaskDoesNotPollAndArrivalsScheduleOnlyOneWorker() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    final List<Integer> received = new ArrayList<>();
    final Task<Integer> task = recordingTask(received);
    assertNotNull(task.id());
    task.start(executor);
    task.start(executor);
    assertEquals(1, executor.pending());
    executor.runNext();
    assertEquals(0, executor.pending());
    assertTrue(received.isEmpty());

    task.accept(1);
    task.accept(2);
    assertEquals(1, executor.pending());
    assertTrue(received.isEmpty(), "accept must not process inline");
    executor.runNext();
    assertEquals(List.of(1, 2), received);
    assertEquals(0, executor.pending());

    task.accept(3);
    assertEquals(1, executor.pending(), "An idle worker must wake on the next arrival");
    executor.runNext();
    assertEquals(List.of(1, 2, 3), received);
    task.stop();
    executor.runNext();
    assertTrue(task.awaitStopped(Duration.ZERO));
    assertFalse(executor.isShutdown(), "A task must not close a shared executor");
  }

  @Test
  public void stopRetainsQueuedMessagesAndRestartRunsHooksOnce() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    final List<Integer> received = new ArrayList<>();
    final List<String> hooks = new ArrayList<>();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void beforeStart() {
            hooks.add("before");
          }

          @Override
          protected void initialize() {
            hooks.add("initialize");
          }

          @Override
          protected void process(final Integer message) {
            received.add(message);
            stop();
          }

          @Override
          protected void cleanup() {
            hooks.add("cleanup");
          }

          @Override
          protected void afterStop() {
            hooks.add("after");
          }
        };
    task.accept(1);
    task.accept(2);
    task.start(executor);
    executor.runNext();
    assertEquals(TaskState.STOPPED, task.state());
    assertEquals(List.of(1), received);
    assertEquals(1, task.inboxSize());
    assertEquals(List.of("before", "initialize", "cleanup", "after"), hooks);
    task.stop();
    assertEquals(0, executor.pending());
    task.start(executor);
    executor.runNext();
    assertEquals(List.of(1, 2), received);
    assertEquals(8, hooks.size());
    assertTrue(task.awaitStopped(Duration.ZERO));
  }

  @Test
  public void failedMessageIsNotRetriedAndFailureCanBeRestarted() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    final List<Integer> received = new ArrayList<>();
    final IllegalStateException failure = new IllegalStateException("bad message");
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer message) {
            if (message == 1) {
              throw failure;
            }
            received.add(message);
          }
        };
    task.accept(1);
    task.accept(2);
    task.start(executor);
    executor.runNext();
    assertEquals(TaskState.FAILED, task.state());
    assertSame(failure, task.failure());
    assertTrue(task.awaitStopped(Duration.ZERO));
    assertEquals(1, task.inboxSize());
    task.start(executor);
    executor.runNext();
    assertEquals(List.of(2), received);
    assertNull(task.failure());
    task.stop();
    executor.runNext();
  }

  @Test
  public void lifecycleFailuresStillCompleteAndPreserveTheirCauses() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    final IllegalStateException failure = new IllegalStateException("init failed");
    final AtomicInteger after = new AtomicInteger();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void initialize() {
            throw failure;
          }

          @Override
          protected void process(final Integer message) {
            throw new AssertionError("Initialization failed; processing must not run");
          }

          @Override
          protected void cleanup() {
            throw new IllegalArgumentException("cleanup failed");
          }

          @Override
          protected void afterStop() {
            after.incrementAndGet();
            throw new IllegalArgumentException("after failed");
          }
        };
    task.accept(1);
    task.start(executor);
    executor.runNext();
    assertEquals(TaskState.FAILED, task.state());
    assertSame(failure, task.failure());
    assertEquals(2, failure.getSuppressed().length);
    assertEquals(1, after.get());
    assertEquals(1, task.inboxSize());
    assertTrue(task.awaitStopped(Duration.ZERO));
  }

  @Test
  public void fullMailboxRejectsWithoutLosingAcceptedMessages() {
    final List<Integer> received = new ArrayList<>();
    final Task<Integer> task =
        new Task<>(1) {
          @Override
          protected void process(final Integer message) {
            received.add(message);
          }
        };
    task.accept(1);
    assertThrows(RejectedExecutionException.class, () -> task.accept(2));
    assertThrows(NullPointerException.class, () -> task.accept(null));
    final ManualExecutor executor = new ManualExecutor();
    task.start(executor);
    executor.runNext();
    assertEquals(List.of(1), received);
    task.stop();
    executor.runNext();
    assertThrows(IllegalArgumentException.class, () -> new RelayTask<>(x -> x, 0));
  }

  @Test
  public void busyMailboxYieldsToOtherTasks() {
    final ManualExecutor executor = new ManualExecutor();
    final List<Integer> busyMessages = new ArrayList<>();
    final List<Integer> otherMessages = new ArrayList<>();
    final Task<Integer> busy = recordingTask(busyMessages);
    final Task<Integer> other = recordingTask(otherMessages);
    for (int index = 0; index < 200; index++) {
      busy.accept(index);
    }
    other.accept(999);
    busy.start(executor);
    other.start(executor);
    executor.runNext();
    assertTrue(busyMessages.size() > 0 && busyMessages.size() < 200);
    executor.runNext();
    assertEquals(List.of(999), otherMessages);
    while (executor.pending() > 0) {
      executor.runNext();
    }
    assertEquals(200, busyMessages.size());
    busy.stop();
    other.stop();
    while (executor.pending() > 0) {
      executor.runNext();
    }
  }

  @Test
  public void rejectedSchedulingFailsVisiblyWithoutStrandingShutdown() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    executor.shutdown();
    final Task<Integer> task = recordingTask(new ArrayList<>());
    task.start(executor);
    assertEquals(TaskState.FAILED, task.state());
    assertTrue(task.failure() instanceof RejectedExecutionException);
    assertTrue(task.awaitStopped(Duration.ZERO));
  }

  @Test
  public void shutdownBetweenBatchesDoesNotTurnNormalStopIntoFailure() throws Exception {
    final List<Integer> received = new ArrayList<>();
    final Task<Integer> task = recordingTask(received);
    final AtomicInteger submissions = new AtomicInteger();
    final ManualExecutor executor =
        new ManualExecutor() {
          @Override
          public void execute(final Runnable command) {
            if (submissions.incrementAndGet() == 2) {
              task.stop();
              shutdown();
            }
            super.execute(command);
          }
        };
    for (int index = 0; index < 100; index++) {
      task.accept(index);
    }
    task.start(executor);
    executor.runNext();
    assertEquals(TaskState.STOPPED, task.state());
    assertNull(task.failure());
    assertTrue(task.awaitStopped(Duration.ZERO));
    assertTrue(task.inboxSize() > 0);
    assertEquals(100, received.size() + task.inboxSize());
  }

  @Test
  public void arrivalsRacingWithWorkerExitAlwaysWakeTheTask() throws Exception {
    final LinkedBlockingQueue<Integer> delivered = new LinkedBlockingQueue<>();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer message) {
            delivered.add(message);
          }
        };
    final ExecutorService workers = Executors.newFixedThreadPool(4);
    try {
      task.start(workers);
      for (int index = 0; index < 500; index++) {
        task.accept(index);
        // The next accept races with the previous worker's transition back to idle.
        assertEquals(Integer.valueOf(index), delivered.poll(5, TimeUnit.SECONDS));
      }
    } finally {
      task.stop();
      assertTrue(task.awaitStopped(TIMEOUT));
      workers.shutdown();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertNull(task.failure());
    assertEquals(0, task.inboxSize());
  }

  @Test
  public void concurrentProducersNeverOverlapProcessingOrLoseMessages() throws Exception {
    final int producers = 4;
    final int messagesPerProducer = 200;
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maximum = new AtomicInteger();
    final List<Integer> received = new ArrayList<>();
    final CountDownLatch delivered = new CountDownLatch(producers * messagesPerProducer);
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer message) {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            received.add(message);
            active.decrementAndGet();
            delivered.countDown();
          }
        };
    final ExecutorService workers = Executors.newFixedThreadPool(4);
    final ExecutorService senders = Executors.newFixedThreadPool(producers);
    try {
      task.start(workers);
      final CountDownLatch start = new CountDownLatch(1);
      final List<Future<?>> submissions = new ArrayList<>();
      for (int producer = 0; producer < producers; producer++) {
        final int offset = producer * messagesPerProducer;
        submissions.add(
            senders.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  for (int index = 0; index < messagesPerProducer; index++) {
                    task.accept(offset + index);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (final Future<?> submission : submissions) {
        submission.get(5, TimeUnit.SECONDS);
      }
      assertTrue(delivered.await(5, TimeUnit.SECONDS));
      task.stop();
      assertTrue(task.awaitStopped(TIMEOUT));
      assertEquals(1, maximum.get());
      assertEquals(producers * messagesPerProducer, received.stream().distinct().count());
      for (int producer = 0; producer < producers; producer++) {
        int previousIndex = -1;
        for (int index = 0; index < messagesPerProducer; index++) {
          final int position = received.indexOf(producer * messagesPerProducer + index);
          assertTrue(
              position > previousIndex, "Each producer's messages must remain in FIFO order");
          previousIndex = position;
        }
      }
    } finally {
      task.stop();
      senders.shutdownNow();
      workers.shutdown();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void stopIsNonBlockingAndWaitIsBoundedWhileProcessingFinishes() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer message) throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
          }
        };
    task.accept(1);
    task.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      task.stop();
      assertEquals(TaskState.STOPPING, task.state());
      assertFalse(task.awaitStopped(Duration.ZERO));
      assertThrows(IllegalStateException.class, task::start);
      assertThrows(IllegalArgumentException.class, () -> task.awaitStopped(Duration.ofSeconds(-1)));
    } finally {
      release.countDown();
      task.stop();
      assertTrue(task.awaitStopped(TIMEOUT));
    }
    assertEquals(TaskState.STOPPED, task.state());
  }

  @Test
  public void stopBeforeStartIsSafeAndMessagesCanArriveWhileStopped() throws Exception {
    final List<Integer> received = new ArrayList<>();
    final Task<Integer> task = recordingTask(received);
    task.stop();
    assertEquals(TaskState.STOPPED, task.state());
    assertTrue(task.awaitStopped(Duration.ZERO));
    task.accept(1);
    final ManualExecutor executor = new ManualExecutor();
    task.start(executor);
    executor.runNext();
    assertEquals(List.of(1), received);
    task.stop();
    executor.runNext();
  }

  private static Task<Integer> recordingTask(final List<Integer> received) {
    return new Task<>() {
      @Override
      protected void process(final Integer message) {
        received.add(message);
      }
    };
  }

  @Test
  public void controlNotificationsSurviveStopAndYieldAfterABatch() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    final AtomicInteger controls = new AtomicInteger();
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer message) {}

          @Override
          protected void onSignal() {
            super.onSignal();
            if (controls.incrementAndGet() < 65) {
              signal();
            }
          }
        };
    task.signal();
    assertEquals(0, executor.pending());
    task.start(executor);
    executor.runNext();
    assertEquals(64, controls.get());
    assertEquals(1, executor.pending(), "Control events must yield after a bounded batch too");
    executor.runNext();
    assertEquals(65, controls.get());
    task.stop();
    executor.runNext();
    task.signal();
    task.start(executor);
    executor.runNext();
    assertEquals(66, controls.get());
    task.stop();
    executor.runNext();
    assertTrue(task.awaitStopped(Duration.ZERO));
  }

  @Test
  public void cleanupFailureBecomesPrimaryAndRepeatedExceptionIsNotSelfSuppressed() {
    final ManualExecutor executor = new ManualExecutor();
    final IllegalStateException failure = new IllegalStateException("shared hook failure");
    final Task<Integer> task =
        new Task<>() {
          @Override
          protected void process(final Integer message) {
            stop();
          }

          @Override
          protected void cleanup() {
            throw failure;
          }

          @Override
          protected void afterStop() {
            throw failure;
          }
        };
    task.accept(1);
    task.start(executor);
    executor.runNext();
    assertEquals(TaskState.FAILED, task.state());
    assertSame(failure, task.failure());
    assertEquals(0, failure.getSuppressed().length);
  }
}
