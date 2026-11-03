package com.cynthiasystems.agentflow.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.tasks.JoinPolicy;
import com.cynthiasystems.agentflow.tasks.TaskState;

public class LoopDemoTest {
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final JoinPolicy POLICY = new JoinPolicy(Duration.ofMinutes(1), 32, 64, 256);
  // Independent, explicit graph specification, rather than reading the runtime's wiring.
  private static final int[][] INPUTS = {
    {9, 8}, {0, 9, 8}, {1, 0, 9, 8}, {2, 1, 0, 9, 8}, {3, 2},
    {4, 3, 2}, {5, 4, 3, 2}, {6, 5, 4, 3, 2}, {7, 6}, {8, 7, 6}
  };

  @Test
  public void feedbackGraphMatchesSynchronousOracleAcrossWorkerCounts() throws Exception {
    final long[][] expected = reference(1000);
    for (final int parallelism : new int[] {1, 2, 4, 8}) {
      try (LoopDemo loop = new LoopDemo(parallelism, 1000)) {
        loop.start();
        assertEquals(
            Map.of("A", 2, "B", 3, "C", 4, "D", 5, "E", 2, "F", 3, "G", 4, "H", 5, "I", 2, "J", 3),
            loop.dependencyCounts());
        verifyValues(loop.snapshot(), expected, TaskState.STARTED);
        loop.input().accept(true);
        loop.input().accept(true); // Idempotent start must never seed a second logical run.
        loop.awaitStepLimit(WAIT);
        loop.input().accept(false);
        assertTrue(loop.awaitStopped(WAIT));
        final Map<String, LoopDemo.NodeState> snapshot = loop.snapshot();
        verifyValues(snapshot, expected, TaskState.STOPPED);
        for (final LoopDemo.NodeState node : snapshot.values()) {
          assertEquals(1000, node.step());
          assertEquals(0, node.queuedData());
          assertEquals(0, node.queuedAdvisories());
        }
        assertEquals(TaskState.STOPPED, loop.input().state());
      }
    }
  }

  @Test
  public void stopIsResponsiveWithOccupiedPoolAndFinishesCurrentComputation() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    try (LoopDemo loop = new LoopDemo(1, 100, blockingStep(entered, release), POLICY, WAIT)) {
      try {
        loop.start();
        loop.input().accept(true);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        loop.input().accept(false);
        assertTrue(loop.input().awaitStopped(WAIT));
        assertFalse(loop.awaitStopped(Duration.ZERO));
      } finally {
        release.countDown();
      }
      assertTrue(loop.awaitStopped(WAIT));
      final Map<String, LoopDemo.NodeState> snapshot = loop.snapshot();
      verifyValues(snapshot, reference(100), TaskState.STOPPED);
      assertEquals(1, snapshot.get("A").step());
      assertTrue(snapshot.values().stream().anyMatch(node -> node.queuedData() > 0));
      assertEquals(snapshot, loop.snapshot());
    }
  }

  @Test
  public void killBroadcastInterruptsBlockedWorkerAndClearsAllInboxes() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    try (LoopDemo loop = new LoopDemo(1, 100, blockingStep(entered, release), POLICY, WAIT)) {
      try {
        loop.start();
        loop.input().accept(true);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        loop.input().advise(LoopDemo.Control.KILL_ALL);
        assertTrue(loop.awaitStopped(WAIT));
        final Map<String, LoopDemo.NodeState> snapshot = loop.snapshot();
        verifyValues(snapshot, reference(100), TaskState.CANCELLED);
        assertEquals(0, snapshot.get("A").step());
        assertTrue(
            snapshot.values().stream()
                .allMatch(node -> node.queuedData() == 0 && node.queuedAdvisories() == 0));
        assertEquals(TaskState.CANCELLED, loop.input().state());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  public void stopBeforeStartLeavesInitialValuesAndOptionalHintDoesNotStartAnything()
      throws Exception {
    assertThrows(IllegalArgumentException.class, () -> new LoopDemo(1, 0));
    try (LoopDemo loop = new LoopDemo(2, 10)) {
      loop.input().advise("diagnostic hint");
      loop.input().accept(false);
      loop.start();
      assertTrue(loop.awaitStopped(WAIT));
      verifyValues(loop.snapshot(), reference(0), TaskState.STOPPED);
    }
  }

  @Test
  public void joinTimeoutIsReportedInsteadOfWaitingForeverForStepLimit() throws Exception {
    final JoinPolicy immediate = new JoinPolicy(Duration.ofNanos(1), 32, 64, 256);
    try (LoopDemo loop = new LoopDemo(1, 10, (name, step) -> {}, immediate, WAIT)) {
      loop.start();
      loop.input().accept(true);
      final ExecutionException error =
          assertThrows(ExecutionException.class, () -> loop.awaitStepLimit(WAIT));
      assertTrue(error.getCause().getMessage().contains("TIMEOUT"));
    }
  }

  @Test
  public void closeReportsUnfinishedComputationAndCanBeRetriedAfterItReturns() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final LoopDemo loop =
        new LoopDemo(1, 10, blockingStep(entered, release), POLICY, Duration.ofMillis(5));
    try {
      loop.start();
      loop.input().accept(true);
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      final IllegalStateException error = assertThrows(IllegalStateException.class, loop::close);
      assertTrue(error.getMessage().contains("still stopping"));
    } finally {
      release.countDown();
      assertTrue(loop.awaitStopped(WAIT));
      loop.close();
    }
  }

  @Test
  public void runnableDemoPrintsEveryFinalNodeWithDefaultAndExplicitStepLimits() throws Exception {
    final PrintStream original = System.out;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      LoopDemo.main(new String[0]);
      LoopDemo.main(new String[] {"7"});
    } finally {
      System.setOut(original);
    }
    final String[] lines = bytes.toString(StandardCharsets.UTF_8).split("\\R");
    assertEquals(20, lines.length);
    for (int i = 0; i < 20; i++) {
      final int step = i < 10 ? 100 : 7;
      final int node = i % 10;
      assertEquals(
          name(node)
              + " "
              + new LoopDemo.NodeState(step, reference(step)[step][node], TaskState.STOPPED, 0, 0),
          lines[i]);
    }
  }

  private static BiConsumer<String, Long> blockingStep(
      final CountDownLatch entered, final CountDownLatch release) {
    return (name, step) -> {
      if (name.equals("A") && step == 1) {
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Test did not release worker");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    };
  }

  private static void verifyValues(
      final Map<String, LoopDemo.NodeState> snapshot,
      final long[][] expected,
      final TaskState state) {
    assertEquals(10, snapshot.size());
    for (int index = 0; index < 10; index++) {
      final LoopDemo.NodeState node = snapshot.get(name(index));
      assertEquals(expected[(int) node.step()][index], node.value(), name(index));
      assertEquals(state, node.state(), name(index));
    }
  }

  private static long[][] reference(final int steps) {
    final long[][] values = new long[steps + 1][10];
    for (int index = 0; index < 10; index++) {
      values[0][index] = index + 1;
    }
    for (int step = 1; step <= steps; step++) {
      for (int node = 0; node < 10; node++) {
        long next = 31L * (node + 1);
        for (final int publisher : INPUTS[node]) {
          next += (publisher + 1) * values[step - 1][publisher];
        }
        values[step][node] = next % 1_000_003;
      }
    }
    return values;
  }

  private static String name(final int index) {
    return String.valueOf((char) ('A' + index));
  }
}
