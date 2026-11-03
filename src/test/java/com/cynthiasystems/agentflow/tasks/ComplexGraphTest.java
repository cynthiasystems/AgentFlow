package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.Agent;

/**
 * Ten processing nodes plus a collector, with reconverging dependencies: E(A,B), F(B,C,D),
 * G(A,B,C,D), H(E,F,C), I(G,H), J(I,E,F,G).
 */
public class ComplexGraphTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final List<String> SOURCES = List.of("A", "B", "C", "D");
  private static final List<String> JOINS = List.of("E", "F", "G", "H", "I", "J");
  private static final long[] SEEDS = {7L, 41L, 2026L};
  // Independently expanded affine formulas, rather than calling the graph's combining functions.
  private static final Map<String, Formula> ORACLE =
      Map.of(
          "A",
          new Formula(2, 1),
          "B",
          new Formula(3, 7),
          "C",
          new Formula(5, 11),
          "D",
          new Formula(7, 13),
          "E",
          new Formula(61, 102),
          "F",
          new Formula(307, 627),
          "G",
          new Formula(623, 1186),
          "H",
          new Formula(17_317, 34_438),
          "I",
          new Formula(1_093_094, 2_170_692),
          "J",
          new Formula(73_313_257, 145_583_071));

  @Test
  public void tenWorkersHandleShuffledDuplicateRequestsFromConcurrentProducers() throws Exception {
    final int requestCount = Integer.getInteger("agentflow.stress.requests", 256);
    assertTrue(requestCount > 0, "agentflow.stress.requests must be positive");
    for (final int workers : new int[] {2, 4, 8}) {
      for (final long seed : SEEDS) {
        runConcurrentScenario(workers, seed, requestCount);
      }
    }
  }

  @Test
  public void theSameGraphCompletesWithOnlyOneSharedWorker() throws Exception {
    final int requestCount = Integer.getInteger("agentflow.stress.requests", 256);
    assertTrue(requestCount > 0);
    for (final long seed : SEEDS) {
      runConcurrentScenario(1, seed, requestCount);
    }
  }

  private static void runConcurrentScenario(final int workers, final long seed, final int count)
      throws Exception {
    final Scheduling schedule = new Scheduling(workers >= 4, false, seed);
    try (Network graph =
        new Network(count + 1, "workers=" + workers + ", seed=" + seed, schedule)) {
      graph.start(workers);
      graph.sendConcurrently(SOURCES, ids(count), seed, true);
      graph.finishAndVerify(ids(count), count);
      assertTrue(graph.maximumActive.get() <= workers, graph.context);
      if (workers >= 4) {
        assertTrue(
            graph.maximumActive.get() >= 4,
            "The barrier must actually force four nodes to execute concurrently: " + graph.context);
      }
    }
  }

  @Test
  public void holdingTheFourthPublisherDoesNotBlockTheIndependentTwoInputJoin() throws Exception {
    final int count = 64;
    final Scheduling schedule = new Scheduling(false, true, 17);
    try (Network graph = new Network(count + 1, "held publisher D", schedule)) {
      graph.start(4);
      graph.sendConcurrently(SOURCES, ids(count), 17, true);
      assertTrue(schedule.fourthEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
      graph.awaitStage("E", ids(count));
      assertEquals(count, graph.probes.get("E").calls.size());
      for (final String waiting : List.of("F", "G", "H", "I", "J", "collector")) {
        assertTrue(graph.probes.get(waiting).calls.isEmpty(), waiting + " ran before D published");
      }
      schedule.releaseFourth.countDown();
      graph.finishAndVerify(ids(count), count);
    }
  }

  @Test
  public void restartingWithPartialJoinsAndReplayedPublicationsProducesOneResultPerRequest()
      throws Exception {
    final int count = 64;
    try (Network graph =
        new Network(count + 1, "partial restart", new Scheduling(false, false, 31))) {
      graph.start(4);
      graph.sendConcurrently(List.of("A", "B", "C"), ids(count), 31, false);
      graph.awaitStage("E", ids(count));
      graph.stopAndWait();
      assertTrue(graph.results.isEmpty());
      graph.start(4);
      // A/B/C are intentional replays; D supplies the missing dependency after restart.
      graph.sendConcurrently(SOURCES, ids(count), 47, true);
      graph.finishAndVerify(ids(count), count);
    }
  }

  @Test
  public void missingInputExpiresAffectedJoinsWithoutMixingRequestsOrResurrectingLateResults() {
    final ManualJoinScheduler clock = new ManualJoinScheduler();
    final ManualExecutor executor = new ManualExecutor();
    try (Network graph = new Network(4, "missing D", clock)) {
      graph.start(executor);
      for (final String source : SOURCES) {
        graph.send(source, 0);
      }
      for (final String source : List.of("A", "B", "C")) {
        graph.send(source, 1);
      }
      graph.drain();
      assertEquals(Set.of(0), graph.results.keySet());
      assertEquals(expected("J", 0), graph.results.get(0).value());
      clock.advance(Duration.ofSeconds(10));
      graph.drain();
      final Map<String, Set<String>> missing = new HashMap<>();
      for (final FailureAt failure : graph.failures) {
        assertEquals(Integer.valueOf(1), failure.failure().requestId());
        assertEquals(JoinFailure.Reason.TIMEOUT, failure.failure().reason());
        assertNull(missing.put(failure.node(), failure.failure().missingPublishers()));
      }
      assertEquals(
          Map.of("F", Set.of("D"), "G", Set.of("D"), "H", Set.of("F"), "J", Set.of("I", "F", "G")),
          missing);
      graph.send("D", 1);
      graph.drain();
      assertEquals(Set.of(0), graph.results.keySet(), "Late D must not reopen expired joins");
      for (final String source : SOURCES) {
        graph.send(source, 2);
      }
      graph.drain();
      assertEquals(Set.of(0, 2), graph.results.keySet());
      assertEquals(expected("J", 2), graph.results.get(2).value());
      assertEquals(4, graph.failures.size(), "No cascading or duplicate timeout notifications");
      assertEquals(0, clock.activeAlarms());
      graph.stopAndWait();
      graph.assertStoppedAndHealthy();
    }
  }

  private static List<Integer> ids(final int count) {
    return IntStream.range(0, count).boxed().toList();
  }

  private static long base(final int id) {
    return 17L * id + 3;
  }

  private static long expected(final String node, final int id) {
    final Formula formula = ORACLE.get(node);
    return formula.multiplier() * base(id) + formula.offset();
  }

  private record Formula(long multiplier, long offset) {}

  private record Request(int id, long value) {}

  private record Value(int id, long value) {}

  private record FailureAt(String node, JoinFailure<Integer> failure) {}

  private static final class Probe {
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximum = new AtomicInteger();
    private final AtomicInteger total = new AtomicInteger();
    private final Map<Integer, Integer> calls = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> finished = new ConcurrentHashMap<>();

    private CompletableFuture<Void> finished(final int id) {
      return finished.computeIfAbsent(id, unused -> new CompletableFuture<>());
    }
  }

  private static final class Scheduling {
    private final CyclicBarrier firstWave;
    private final boolean holdFourth;
    private final long seed;
    private final CountDownLatch fourthEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFourth = new CountDownLatch(1);

    private Scheduling(final boolean forceOverlap, final boolean holdFourth, final long seed) {
      firstWave = forceOverlap ? new CyclicBarrier(4) : null;
      this.holdFourth = holdFourth;
      this.seed = seed;
    }

    private void beforeLeaf(final String name, final int id, final boolean first) {
      try {
        if (first && firstWave != null) {
          firstWave.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (holdFourth && name.equals("D")) {
          fourthEntered.countDown();
          assertTrue(
              releaseFourth.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "D gate timed out");
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } catch (final Exception e) {
        throw new IllegalStateException(e);
      }
      // Seeded yield points vary scheduling; barriers and latches enforce the important
      // interleavings.
      if (Math.floorMod(id * 17L + name.hashCode() + seed, 11) == 0) {
        Thread.yield();
      }
    }

    private void release() {
      releaseFourth.countDown();
      if (firstWave != null) {
        firstWave.reset();
      }
    }
  }

  private static final class Network implements AutoCloseable {
    private final String context;
    private final Scheduling schedule;
    private final JoinScheduler scheduler;
    private final boolean allowTimeouts;
    private final JoinPolicy policy;
    private final List<Task<?>> tasks = new ArrayList<>();
    private final Map<String, RelayTask<Request, Value>> sources = new LinkedHashMap<>();
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private final Map<String, Map<Integer, Integer>> submitted = new LinkedHashMap<>();
    private final Map<Integer, Value> results = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<FailureAt> failures = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<Void> broken = new CompletableFuture<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();
    private Agent agent;
    private ManualExecutor manual;

    private Network(final int requests, final String context, final Scheduling schedule) {
      this(requests, context, schedule, SystemJoinScheduler.INSTANCE, false);
    }

    private Network(final int requests, final String context, final ManualJoinScheduler scheduler) {
      this(requests, context, new Scheduling(false, false, 0), scheduler, true);
    }

    private Network(
        final int requests,
        final String context,
        final Scheduling schedule,
        final JoinScheduler scheduler,
        final boolean allowTimeouts) {
      this.context = context;
      this.schedule = schedule;
      this.scheduler = scheduler;
      this.allowTimeouts = allowTimeouts;
      // Enough for the entire finite workload: overflow is tested separately, not silently retried.
      policy =
          new JoinPolicy(
              Duration.ofSeconds(allowTimeouts ? 10 : 60),
              requests,
              requests * 2 + 16,
              requests * 8 + 64);
      final RelayTask<Request, Value> a = leaf("A", 2, 1);
      final RelayTask<Request, Value> b = leaf("B", 3, 7);
      final RelayTask<Request, Value> c = leaf("C", 5, 11);
      final RelayTask<Request, Value> d = leaf("D", 7, 13);
      final JoinTask<Integer, Value, Value> e = join("E", Map.of("A", 11L, "B", 13L));
      final JoinTask<Integer, Value, Value> f = join("F", Map.of("B", 17L, "C", 19L, "D", 23L));
      final JoinTask<Integer, Value, Value> g =
          join("G", Map.of("A", 29L, "B", 31L, "C", 37L, "D", 41L));
      final JoinTask<Integer, Value, Value> h = join("H", Map.of("E", 43L, "F", 47L, "C", 53L));
      final JoinTask<Integer, Value, Value> i = join("I", Map.of("G", 59L, "H", 61L));
      final JoinTask<Integer, Value, Value> j =
          join("J", Map.of("I", 67L, "E", 71L, "F", 73L, "G", 79L));
      a.relay(e, Request::id);
      b.relay(e, Request::id);
      b.relay(f, Request::id);
      c.relay(f, Request::id);
      d.relay(f, Request::id);
      a.relay(g, Request::id);
      b.relay(g, Request::id);
      c.relay(g, Request::id);
      d.relay(g, Request::id);
      e.relay(h, JoinInput::requestId);
      f.relay(h, JoinInput::requestId);
      c.relay(h, Request::id);
      g.relay(i, JoinInput::requestId);
      h.relay(i, JoinInput::requestId);
      i.relay(j, JoinInput::requestId);
      e.relay(j, JoinInput::requestId);
      f.relay(j, JoinInput::requestId);
      g.relay(j, JoinInput::requestId);
      assertEquals(Set.of("A", "B"), e.publisherNames());
      assertEquals(Set.of("B", "C", "D"), f.publisherNames());
      assertEquals(Set.of("A", "B", "C", "D"), g.publisherNames());
      assertEquals(Set.of("E", "F", "C"), h.publisherNames());
      assertEquals(Set.of("G", "H"), i.publisherNames());
      assertEquals(Set.of("I", "E", "F", "G"), j.publisherNames());
      assertEquals(10, tasks.size(), "Ten processing workers before adding the collector");
      probes.put("collector", new Probe());
      final Task<Value> collector =
          new Task<>("collector", policy.mailboxCapacity()) {
            @Override
            protected void process(final Value value) {
              observe(
                  "collector",
                  value.id(),
                  () -> {
                    assertEquals(expected("J", value.id()), value.value(), context);
                    assertNull(
                        results.putIfAbsent(value.id(), value),
                        "Duplicate final output: " + value.id());
                    return value;
                  });
            }
          };
      j.relay(collector);
      tasks.add(collector);
    }

    private RelayTask<Request, Value> leaf(
        final String name, final long multiplier, final long offset) {
      probes.put(name, new Probe());
      submitted.put(name, new ConcurrentHashMap<>());
      final RelayTask<Request, Value> node =
          new RelayTask<>(
              name,
              request ->
                  observe(
                      name,
                      request.id(),
                      () -> {
                        schedule.beforeLeaf(name, request.id(), probes.get(name).total.get() == 1);
                        return new Value(request.id(), multiplier * request.value() + offset);
                      }),
              policy.mailboxCapacity());
      sources.put(name, node);
      tasks.add(node);
      return node;
    }

    private JoinTask<Integer, Value, Value> join(
        final String name, final Map<String, Long> weights) {
      probes.put(name, new Probe());
      final JoinTask<Integer, Value, Value> node =
          new JoinTask<>(
              name,
              policy,
              (id, inputs) ->
                  observe(
                      name,
                      id,
                      () -> {
                        assertEquals(
                            weights.keySet(),
                            inputs.keySet(),
                            name + " publisher slots: " + context);
                        long sum = 0;
                        for (final Map.Entry<String, Value> input : inputs.entrySet()) {
                          assertEquals(
                              id.intValue(),
                              input.getValue().id(),
                              name + " mixed request IDs: " + context);
                          assertEquals(
                              expected(input.getKey(), id),
                              input.getValue().value(),
                              name + " wrong input: " + context);
                          sum += weights.get(input.getKey()) * input.getValue().value();
                        }
                        assertEquals(
                            expected(name, id), sum, name + " wrong combined result: " + context);
                        return new Value(id, sum);
                      }),
              failure -> {
                failures.add(new FailureAt(name, failure));
                if (!allowTimeouts) {
                  broken.completeExceptionally(
                      new AssertionError(name + ": " + failure + "; " + context));
                }
              },
              scheduler);
      tasks.add(node);
      return node;
    }

    private Value observe(final String name, final int id, final Supplier<Value> operation) {
      final Probe probe = probes.get(name);
      final int localActive = probe.active.incrementAndGet();
      probe.maximum.accumulateAndGet(localActive, Math::max);
      maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
      final Value result;
      try {
        assertEquals(1, localActive, "Concurrent execution inside " + name + ": " + context);
        probe.total.incrementAndGet();
        final int calls = probe.calls.merge(id, 1, Integer::sum);
        if (!sources.containsKey(name)) {
          assertEquals(1, calls, "Repeated join/collector invocation at " + name + ": " + context);
        }
        result = operation.get();
      } catch (final RuntimeException | Error e) {
        broken.completeExceptionally(e);
        throw e;
      } finally {
        active.decrementAndGet();
        probe.active.decrementAndGet();
      }
      probe.finished(id).complete(null);
      return result;
    }

    private void start(final int workers) {
      if (agent == null) {
        agent = Agent.builder().tasks(tasks).parallelism(workers).build();
      }
      agent.start();
    }

    private void start(final ManualExecutor executor) {
      manual = executor;
      for (final Task<?> task : tasks) {
        task.start(executor);
      }
      drain();
    }

    private void send(final String source, final int id) {
      sources.get(source).accept(new Request(id, base(id)));
      submitted.get(source).merge(id, 1, Integer::sum);
    }

    private void sendConcurrently(
        final List<String> publishers,
        final List<Integer> requests,
        final long seed,
        final boolean duplicate)
        throws Exception {
      final int senderCount = publishers.size() * 2;
      final ExecutorService senders = Executors.newFixedThreadPool(senderCount);
      final CountDownLatch ready = new CountDownLatch(senderCount);
      final CountDownLatch start = new CountDownLatch(1);
      final List<Future<?>> futures = new ArrayList<>();
      try {
        for (final String publisher : publishers) {
          for (int partition = 0; partition < 2; partition++) {
            final List<Integer> order = new ArrayList<>();
            for (final int id : requests) {
              if (id % 2 == partition) {
                order.add(id);
                if (duplicate && id % 7 == 0) {
                  order.add(id);
                }
              }
            }
            Collections.shuffle(
                order, new Random(seed * 31 + publisher.hashCode() * 7L + partition));
            futures.add(
                senders.submit(
                    () -> {
                      ready.countDown();
                      assertTrue(start.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), context);
                      for (final int id : order) {
                        send(publisher, id);
                      }
                      return null;
                    }));
          }
        }
        assertTrue(ready.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), context);
        start.countDown();
        for (final Future<?> future : futures) {
          future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
      } finally {
        start.countDown();
        senders.shutdownNow();
        assertTrue(senders.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS), context);
      }
    }

    private void awaitStage(final String name, final List<Integer> requests) throws Exception {
      final CompletableFuture<?>[] completions =
          requests.stream()
              .map(id -> probes.get(name).finished(id))
              .toArray(CompletableFuture[]::new);
      try {
        CompletableFuture.anyOf(CompletableFuture.allOf(completions), broken)
            .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (final Exception e) {
        throw new AssertionError("Waiting for " + name + ": " + context + "; " + diagnostics(), e);
      }
    }

    private String diagnostics() {
      final StringBuilder text = new StringBuilder();
      for (final Task<?> task : tasks) {
        text.append(task.name())
            .append('=')
            .append(task.state())
            .append("/queued:")
            .append(task.inboxSize())
            .append("/failure:")
            .append(task.failure())
            .append(' ');
      }
      return text.toString();
    }

    private void finishAndVerify(final List<Integer> requests, final int marker) throws Exception {
      // Sent after every producer finishes. At every join, this marker follows all earlier values
      // from every publisher, including late duplicates. Its final output is a graph-wide barrier.
      for (final String source : SOURCES) {
        send(source, marker);
      }
      awaitStage("collector", List.of(marker));
      stopAndWait();
      final Set<Integer> expectedIds = new LinkedHashSet<>(requests);
      assertTrue(expectedIds.add(marker));
      assertEquals(expectedIds, results.keySet(), "Lost or extra final outputs: " + context);
      assertTrue(failures.isEmpty(), "Unexpected join outcomes: " + failures + "; " + context);
      assertTrue(!broken.isCompletedExceptionally(), diagnostics());
      for (final String source : SOURCES) {
        assertEquals(
            submitted.get(source), probes.get(source).calls, source + " lost input: " + context);
      }
      for (final String node : JOINS) {
        assertEquals(
            expectedIds, probes.get(node).calls.keySet(), node + " missing requests: " + context);
        for (final int calls : probes.get(node).calls.values()) {
          assertEquals(1, calls, context);
        }
      }
      for (final Probe probe : probes.values()) {
        assertEquals(0, probe.active.get(), context);
        assertEquals(1, probe.maximum.get(), context);
      }
      assertEquals(0, active.get());
      assertStoppedAndHealthy();
    }

    private void assertStoppedAndHealthy() {
      for (final Task<?> task : tasks) {
        assertNull(task.failure(), task.name() + ": " + context);
        assertEquals(TaskState.STOPPED, task.state(), task.name() + ": " + context);
        assertEquals(0, task.inboxSize(), task.name() + " retained unexpected work: " + context);
      }
    }

    private void drain() {
      int turns = 0;
      while (manual.pending() > 0) {
        assertTrue(++turns < 100_000, "Executor did not become idle: " + context);
        manual.runNext();
      }
      assertTrue(!broken.isCompletedExceptionally(), diagnostics());
    }

    private void stopAndWait() {
      if (agent != null) {
        agent.stop();
        try {
          assertTrue(
              agent.awaitStopped(WAIT), "Shutdown timed out: " + context + "; " + diagnostics());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      } else {
        for (final Task<?> task : tasks) {
          task.stop();
        }
        drain();
      }
    }

    @Override
    public void close() {
      schedule.release();
      stopAndWait();
    }
  }
}
