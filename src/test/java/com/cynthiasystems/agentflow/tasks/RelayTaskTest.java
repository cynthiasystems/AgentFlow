package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.Agent;

public class RelayTaskTest {
  @Test
  public void fanOutAndFanInPreserveMessagesAndSupportBroaderConsumerTypes() {
    final ManualExecutor executor = new ManualExecutor();
    final List<Number> received = new ArrayList<>();
    final Task<Number> sink =
        new Task<>() {
          @Override
          protected void process(final Number message) {
            received.add(message);
          }
        };
    final RelayTask<Integer, Integer> left = RelayTask.of(x -> x * 2);
    final RelayTask<Integer, Integer> right = RelayTask.of(x -> x * 3);
    final RelayTask<Integer, Integer> source = RelayTask.of(x -> x);
    source.relay(left);
    source.relay(right);
    left.relay(sink);
    right.relay(sink);
    source.accept(7);
    final List<Task<?>> tasks = List.of(source, left, right, sink);
    for (final Task<?> task : tasks) {
      task.start(executor);
    }
    while (executor.pending() > 0) {
      executor.runNext();
    }
    assertEquals(List.of(14, 21), received);
    for (final Task<?> task : tasks) {
      task.stop();
    }
    while (executor.pending() > 0) {
      executor.runNext();
    }
  }

  @Test
  public void simultaneousMessagesInACycleDoNotDeadlock() throws Exception {
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final CountDownLatch received = new CountDownLatch(4);
    final RelayTask<Integer, Integer> left = cyclicTask(barrier, received);
    final RelayTask<Integer, Integer> right = cyclicTask(barrier, received);
    left.relay(right);
    right.relay(left);
    left.accept(0);
    right.accept(0);
    final Agent agent = Agent.builder().task(left).task(right).parallelism(2).build();
    try {
      agent.start();
      assertTrue(received.await(5, TimeUnit.SECONDS));
    } finally {
      agent.stop();
      assertTrue(agent.awaitStopped(Duration.ofSeconds(5)));
    }
    assertNull(left.failure());
    assertNull(right.failure());
  }

  @Test
  public void selfRelayRunsWithoutRecursionOnASingleWorker() throws Exception {
    final AtomicInteger count = new AtomicInteger();
    final CountDownLatch complete = new CountDownLatch(1);
    final RelayTask<Integer, Integer> task =
        new RelayTask<>(x -> x + 1) {
          @Override
          protected void process(final Integer message) {
            count.incrementAndGet();
            if (message == 10_000) {
              stop();
              complete.countDown();
            } else {
              super.process(message);
            }
          }
        };
    task.relay(task);
    task.accept(0);
    try {
      task.start();
      assertTrue(complete.await(5, TimeUnit.SECONDS));
      assertTrue(task.awaitStopped(Duration.ofSeconds(5)));
      assertEquals(10_001, count.get());
      assertNull(task.failure());
    } finally {
      task.stop();
      assertTrue(task.awaitStopped(Duration.ofSeconds(5)));
    }
  }

  @Test
  public void downstreamOverflowFailsPublisherAndDoesNotReplayPartialFanOut() {
    final ManualExecutor executor = new ManualExecutor();
    final RelayTask<Integer, Integer> source = RelayTask.of(x -> x);
    final RelayTask<Integer, Integer> first = RelayTask.of(x -> x);
    final RelayTask<Integer, Integer> full = new RelayTask<>(x -> x, 1);
    full.accept(99);
    source.relay(first);
    source.relay(full);
    source.accept(1);
    source.start(executor);
    executor.runNext();
    assertEquals(TaskState.FAILED, source.state());
    assertEquals(1, first.inboxSize());
    assertEquals(1, full.inboxSize());
    assertEquals(0, source.inboxSize());
    source.start(executor);
    executor.runNext();
    assertEquals(1, first.inboxSize());
    source.stop();
    executor.runNext();
    assertThrows(NullPointerException.class, () -> source.relay(null));
    assertThrows(NullPointerException.class, () -> RelayTask.of(null));
  }

  private static RelayTask<Integer, Integer> cyclicTask(
      final CyclicBarrier barrier, final CountDownLatch received) {
    return new RelayTask<>(x -> x + 1) {
      @Override
      protected void process(final Integer message) {
        if (message == 0) {
          try {
            barrier.await(5, TimeUnit.SECONDS);
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        }
        if (message < 1) {
          super.process(message);
        }
        received.countDown();
      }
    };
  }
}
