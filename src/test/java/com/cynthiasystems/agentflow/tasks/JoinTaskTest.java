package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class JoinTaskTest {
  private static final Duration DEADLINE = Duration.ofSeconds(10);
  private static final JoinPolicy POLICY = new JoinPolicy(DEADLINE, 2, 8, 16);
  private static final Set<String> BRANCHES = Set.of("left", "right");

  @Test
  public void overlappingRequestsJoinByIdRegardlessOfBranchArrivalOrder() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 14);
      fixture.send("b", "right", 33);
      assertTrue(fixture.results.isEmpty());
      assertEquals(0, fixture.executor.pending(), "Incomplete joins must leave the worker idle");
      assertEquals(1, fixture.clock.activeAlarms(), "Only the earliest deadline needs a timer");
      fixture.send("b", "left", 22);
      fixture.send("a", "right", 21);
      assertEquals(
          List.of(
              new Result("b", Map.of("left", 22, "right", 33)),
              new Result("a", Map.of("left", 14, "right", 21))),
          fixture.results);
      assertThrows(
          UnsupportedOperationException.class,
          () -> fixture.results.get(0).inputs().put("left", -1));
      assertEquals(0, fixture.clock.activeAlarms());
    }
  }

  @Test
  public void allConfiguredBranchesAreRequiredAndFirstDuplicateWins() {
    try (Fixture fixture = new Fixture(Set.of("left", "right", "extra"), POLICY)) {
      fixture.send("a", "left", 14);
      fixture.send("a", "left", 999);
      fixture.send("a", "right", 21);
      assertTrue(fixture.results.isEmpty());
      fixture.send("a", "extra", 7);
      assertEquals(
          List.of(new Result("a", Map.of("left", 14, "right", 21, "extra", 7))), fixture.results);
      fixture.send("a", "left", 100);
      fixture.send("a", "right", 200);
      fixture.send("a", "extra", 300);
      assertEquals(
          1, fixture.results.size(), "Late duplicates must not reopen a completed request");
    }
  }

  @Test
  public void missingBranchTimesOutWithoutAnyFurtherInputAndLateResultsAreIgnored() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 14);
      fixture.clock.advance(DEADLINE);
      assertTrue(fixture.failures.isEmpty(), "The timer must not execute user callbacks");
      fixture.drain();
      assertEquals(
          List.of(new JoinFailure<>("a", JoinFailure.Reason.TIMEOUT, Set.of("right"))),
          fixture.failures);
      assertEquals(TaskState.STARTED, fixture.join.state());
      assertEquals(0, fixture.clock.activeAlarms());
      fixture.send("a", "right", 21);
      fixture.send("a", "left", 14);
      assertTrue(fixture.results.isEmpty());
      assertEquals(1, fixture.failures.size());
    }
  }

  @Test
  public void duplicateDoesNotExtendTheOriginalDeadline() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 1);
      fixture.clock.advance(Duration.ofSeconds(9));
      fixture.send("a", "left", 2);
      fixture.clock.advance(Duration.ofSeconds(1));
      fixture.drain();
      assertEquals(JoinFailure.Reason.TIMEOUT, fixture.failures.get(0).reason());
      assertTrue(fixture.results.isEmpty());
    }
  }

  @Test
  public void inputProcessedAtTheDeadlineCannotCompleteEvenIfTimerDeliveryIsDelayed() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 1);
      fixture.clock.elapseWithoutFiring(DEADLINE);
      fixture.send("a", "right", 2);
      assertTrue(fixture.results.isEmpty());
      assertEquals(JoinFailure.Reason.TIMEOUT, fixture.failures.get(0).reason());
    }
  }

  @Test
  public void eachRequestKeepsItsOwnDeadlineAndTimeoutFreesCapacity() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 1);
      fixture.clock.advance(Duration.ofSeconds(3));
      fixture.send("b", "right", 2);
      fixture.clock.advance(Duration.ofSeconds(7));
      fixture.drain();
      assertEquals("a", fixture.failures.get(0).requestId());
      assertEquals(1, fixture.clock.activeAlarms());
      fixture.send("c", "left", 3);
      fixture.send("c", "right", 4);
      assertEquals("c", fixture.results.get(0).requestId());
      fixture.clock.advance(Duration.ofSeconds(3));
      fixture.drain();
      assertEquals("b", fixture.failures.get(1).requestId());
      assertEquals(Set.of("left"), fixture.failures.get(1).missingPublishers());
      assertEquals(0, fixture.clock.activeAlarms());
    }
  }

  @Test
  public void incompleteLimitRejectsNewRequestsWithoutDiscardingExistingOnes() {
    try (Fixture fixture = new Fixture(BRANCHES, new JoinPolicy(DEADLINE, 1, 8, 16))) {
      fixture.send("a", "left", 1);
      fixture.send("b", "left", 2);
      assertEquals(
          List.of(new JoinFailure<>("b", JoinFailure.Reason.CAPACITY, BRANCHES)), fixture.failures);
      fixture.send("a", "right", 3);
      fixture.send("b", "right", 4);
      fixture.send("c", "left", 5);
      fixture.send("c", "right", 6);
      assertEquals(List.of("a", "c"), fixture.results.stream().map(Result::requestId).toList());
      assertEquals(1, fixture.failures.size());
      assertNull(fixture.join.failure());
    }
  }

  @Test
  public void timeoutNotificationCannotBeLostWhenMailboxIsFull() {
    try (Fixture fixture = new Fixture(BRANCHES, new JoinPolicy(DEADLINE, 1, 8, 1))) {
      fixture.send("a", "left", 1);
      fixture.join.accept(fixture.input("b", "left", 2));
      assertEquals(1, fixture.join.inboxSize());
      fixture.clock.advance(DEADLINE);
      fixture.drain();
      assertEquals(1, fixture.failures.size());
      assertEquals("a", fixture.failures.get(0).requestId());
      fixture.send("b", "right", 3);
      assertEquals("b", fixture.results.get(0).requestId());
      assertNull(fixture.join.failure());
    }
  }

  @Test
  public void restartPreservesPartialInputsAndCompletedRequestHistory() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("complete", "left", 1);
      fixture.send("complete", "right", 2);
      fixture.send("partial", "right", 3);
      fixture.join.stop();
      fixture.drain();
      assertEquals(0, fixture.clock.activeAlarms());
      fixture.clock.advance(Duration.ofSeconds(5));
      fixture.join.accept(fixture.input("partial", "left", 4));
      fixture.join.start(fixture.executor);
      fixture.drain();
      fixture.send("complete", "left", 5);
      fixture.send("complete", "right", 6);
      assertEquals(2, fixture.results.size());
      assertEquals(new Result("partial", Map.of("left", 4, "right", 3)), fixture.results.get(1));
      assertTrue(fixture.failures.isEmpty());
    }
  }

  @Test
  public void restartingAfterDeadlineExpiresBeforeProcessingQueuedResults() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 1);
      fixture.join.stop();
      fixture.drain();
      fixture.clock.advance(DEADLINE);
      assertTrue(fixture.failures.isEmpty(), "Stopped joins must not run callbacks");
      fixture.join.accept(fixture.input("a", "right", 2));
      fixture.join.start(fixture.executor);
      fixture.drain();
      assertEquals(JoinFailure.Reason.TIMEOUT, fixture.failures.get(0).reason());
      assertTrue(fixture.results.isEmpty());
      assertNull(fixture.join.failure());
    }
  }

  @Test
  public void cancelledTimerRacingWithRestartCannotExpireANewerRequestEarly() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 1);
      final Runnable staleNotification = fixture.clock.nextNotification();
      fixture.send("a", "right", 2);
      fixture.join.stop();
      fixture.drain();
      fixture.clock.advance(DEADLINE);
      fixture.join.start(fixture.executor);
      fixture.drain();
      fixture.send("b", "left", 3);
      staleNotification.run();
      staleNotification.run();
      fixture.drain();
      assertTrue(fixture.failures.isEmpty());
      assertEquals(1, fixture.clock.activeAlarms());
      fixture.send("b", "right", 4);
      assertEquals(2, fixture.results.size());
    }
  }

  @Test
  public void boundedHistoryEvictsOldestFinishedIdsAndDoesNotPromisePermanentDeduplication() {
    try (Fixture fixture = new Fixture(BRANCHES, new JoinPolicy(DEADLINE, 2, 1, 16))) {
      fixture.send("a", "left", 1);
      fixture.send("a", "right", 2);
      fixture.send("b", "left", 3);
      fixture.send("b", "right", 4);
      fixture.send("a", "left", 5);
      fixture.send("a", "right", 6);
      assertEquals(
          List.of("a", "b", "a"), fixture.results.stream().map(Result::requestId).toList());
    }
  }

  @Test
  public void invalidBranchFailsVisiblyAndRestartCanCompleteOtherPartialRequests() {
    try (Fixture fixture = new Fixture()) {
      fixture.send("a", "left", 1);
      fixture.join.accept(new JoinInput<>("bad", "unknown-id", "unknown", 2));
      fixture.drain();
      assertEquals(TaskState.FAILED, fixture.join.state());
      assertTrue(fixture.join.failure() instanceof IllegalArgumentException);
      assertEquals(0, fixture.clock.activeAlarms());
      fixture.join.start(fixture.executor);
      fixture.drain();
      fixture.send("a", "right", 3);
      assertEquals(1, fixture.results.size());
    }
  }

  @Test
  public void failingCombinerIsNotReplayedAfterRestart() {
    final ManualExecutor executor = new ManualExecutor();
    final ManualJoinScheduler clock = new ManualJoinScheduler();
    final List<String> invoked = new ArrayList<>();
    final JoinTask<String, Integer, Integer> join =
        new JoinTask<>(
            "join",
            POLICY,
            (id, inputs) -> {
              invoked.add(id);
              throw new IllegalStateException("combiner failed");
            },
            failure -> {},
            clock);
    final Map<String, RelayTask<Request, Integer>> publishers = wire(join, BRANCHES);
    join.accept(publication(publishers, "a", "left", 1));
    join.accept(publication(publishers, "a", "right", 2));
    join.start(executor);
    executor.runNext();
    assertEquals(TaskState.FAILED, join.state());
    assertEquals(0, clock.activeAlarms());
    join.accept(publication(publishers, "a", "left", 1));
    join.accept(publication(publishers, "a", "right", 2));
    join.start(executor);
    executor.runNext();
    assertEquals(List.of("a"), invoked);
    join.stop();
    executor.runNext();
  }

  @Test
  public void failingTimeoutHandlerIsVisibleAndNotificationIsNotReplayed() {
    final ManualExecutor executor = new ManualExecutor();
    final ManualJoinScheduler clock = new ManualJoinScheduler();
    final List<String> notified = new ArrayList<>();
    final JoinTask<String, Integer, Integer> join =
        new JoinTask<>(
            "join",
            POLICY,
            (id, inputs) -> 0,
            failure -> {
              notified.add(failure.requestId());
              throw new IllegalStateException("failure handler failed");
            },
            clock);
    final Map<String, RelayTask<Request, Integer>> publishers = wire(join, BRANCHES);
    join.accept(publication(publishers, "a", "left", 1));
    join.start(executor);
    executor.runNext();
    clock.advance(DEADLINE);
    executor.runNext();
    assertEquals(TaskState.FAILED, join.state());
    assertEquals(0, clock.activeAlarms());
    join.start(executor);
    executor.runNext();
    assertEquals(List.of("a"), notified);
    join.stop();
    executor.runNext();
  }

  @Test
  public void realTimerWakesAnIdleJoinWithoutPollingOrFurtherInput() throws Exception {
    final CountDownLatch expired = new CountDownLatch(1);
    final List<JoinFailure<String>> failures = new ArrayList<>();
    final JoinTask<String, Integer, Integer> join =
        new JoinTask<>(
            "join",
            new JoinPolicy(Duration.ofMillis(20), 1, 8, 8),
            (id, inputs) -> 0,
            failure -> {
              failures.add(failure);
              expired.countDown();
            });
    final Map<String, RelayTask<Request, Integer>> publishers = wire(join, BRANCHES);
    try {
      join.accept(publication(publishers, "a", "left", 1));
      join.start();
      assertTrue(expired.await(5, TimeUnit.SECONDS));
      assertEquals(JoinFailure.Reason.TIMEOUT, failures.get(0).reason());
    } finally {
      join.stop();
      assertTrue(join.awaitStopped(Duration.ofSeconds(5)));
    }
    assertNull(join.failure());
  }

  @Test
  public void configurationAndMessagesRejectInvalidValues() {
    assertNotNull(JoinPolicy.defaults());
    assertThrows(IllegalArgumentException.class, () -> new JoinPolicy(Duration.ZERO, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new JoinPolicy(DEADLINE, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new JoinPolicy(DEADLINE, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new JoinPolicy(DEADLINE, 1, 1, 0));
    assertThrows(
        ArithmeticException.class,
        () -> new JoinPolicy(Duration.ofSeconds(Long.MAX_VALUE), 1, 1, 1));
    assertThrows(NullPointerException.class, () -> new JoinInput<>(null, "id", "left", 1));
    assertThrows(NullPointerException.class, () -> new JoinInput<>("a", null, "left", 1));
    assertThrows(NullPointerException.class, () -> new JoinInput<>("a", "id", "left", null));
    assertThrows(IllegalArgumentException.class, () -> new JoinInput<>("a", "id", " ", 1));
    assertThrows(IllegalStateException.class, () -> new Fixture(Set.of(), POLICY));
    assertThrows(IllegalArgumentException.class, () -> new Fixture(Set.of(""), POLICY));
    try (Fixture fixture = new Fixture()) {
      assertThrows(NullPointerException.class, () -> fixture.join.relay(null));
    }
    final JoinFailure<String> failure =
        new JoinFailure<>("a", JoinFailure.Reason.TIMEOUT, BRANCHES);
    assertThrows(UnsupportedOperationException.class, () -> failure.missingPublishers().clear());
  }

  private record Request(String id, int value) {}

  private static Map<String, RelayTask<Request, Integer>> wire(
      final JoinTask<String, Integer, ?> join, final Set<String> names) {
    final Map<String, RelayTask<Request, Integer>> publishers = new LinkedHashMap<>();
    for (final String name : names) {
      final RelayTask<Request, Integer> publisher = RelayTask.of(name, Request::value);
      publisher.relay(join, Request::id);
      publishers.put(name, publisher);
    }
    return publishers;
  }

  private static JoinInput<String, Integer> publication(
      final Map<String, RelayTask<Request, Integer>> publishers,
      final String requestId,
      final String name,
      final int value) {
    return new JoinInput<>(requestId, publishers.get(name).id(), name, value);
  }

  private record Result(String requestId, Map<String, Integer> inputs) {}

  private static final class Fixture implements AutoCloseable {
    private final ManualExecutor executor = new ManualExecutor();
    private final ManualJoinScheduler clock = new ManualJoinScheduler();
    private final List<Result> results = new ArrayList<>();
    private final List<JoinFailure<String>> failures = new ArrayList<>();
    private final JoinTask<String, Integer, Result> join;
    private final Task<Result> sink;
    private final Map<String, RelayTask<Request, Integer>> publishers;

    private Fixture() {
      this(BRANCHES, POLICY);
    }

    private Fixture(final Set<String> branches, final JoinPolicy policy) {
      join = new JoinTask<>("join", policy, Result::new, failures::add, clock);
      publishers = wire(join, branches);
      sink =
          new Task<>() {
            @Override
            protected void process(final Result result) {
              results.add(result);
            }
          };
      join.relay(sink);
      for (final Task<?> publisher : publishers.values()) {
        publisher.start(executor);
      }
      join.start(executor);
      sink.start(executor);
      drain();
    }

    private void send(final String id, final String branch, final int value) {
      publishers.get(branch).accept(new Request(id, value));
      drain();
    }

    private JoinInput<String, Integer> input(final String id, final String name, final int value) {
      return publication(publishers, id, name, value);
    }

    private void drain() {
      while (executor.pending() > 0) {
        executor.runNext();
      }
    }

    @Override
    public void close() {
      for (final Task<?> publisher : publishers.values()) {
        publisher.stop();
      }
      join.stop();
      sink.stop();
      drain();
      assertEquals(0, clock.activeAlarms());
      assertFalse(join.state() == TaskState.STARTED);
    }
  }
}
