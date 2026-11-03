package com.cynthiasystems.agentflow.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import com.cynthiasystems.agentflow.Agent;
import com.cynthiasystems.agentflow.tasks.JoinInput;
import com.cynthiasystems.agentflow.tasks.JoinPolicy;
import com.cynthiasystems.agentflow.tasks.JoinTask;
import com.cynthiasystems.agentflow.tasks.PublishingTask;
import com.cynthiasystems.agentflow.tasks.Task;
import com.cynthiasystems.agentflow.tasks.TaskCommand;
import com.cynthiasystems.agentflow.tasks.TaskState;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Ten feedback workers, a Boolean control input, and no collector node. */
@SuppressWarnings("PMD.LawOfDemeter")
public final class LoopDemo implements AutoCloseable {
  private static final int NODE_COUNT = 10;
  private static final long MODULUS = 1_000_003;
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** Optional commands to the control node, independent of its Boolean data input. */
  public enum Control {
    KILL_ALL
  }

  private final Map<String, JoinTask<Long, Long, Long>> workers = new LinkedHashMap<>();
  private final Map<String, Value> values = new ConcurrentHashMap<>();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicInteger completed = new AtomicInteger();
  private final CompletableFuture<Void> limitReached = new CompletableFuture<>();
  private final PublishingTask<Boolean, Void> input;
  private final Agent agent;
  private final Duration shutdownTimeout;

  public LoopDemo(final int parallelism, final int steps) {
    this(
        parallelism,
        steps,
        (name, step) -> {},
        new JoinPolicy(Duration.ofMinutes(1), 32, 64, 256),
        WAIT);
  }

  LoopDemo(
      final int parallelism,
      final int steps,
      final BiConsumer<String, Long> onStep,
      final JoinPolicy policy,
      final Duration shutdownTimeout) {
    if (steps <= 0) {
      throw new IllegalArgumentException("Step limit must be positive");
    }
    this.shutdownTimeout = shutdownTimeout;
    for (int index = 0; index < NODE_COUNT; index++) {
      final int number = index;
      final String name = name(index);
      values.put(name, new Value(0, index + 1));
      workers.put(
          name,
          new JoinTask<>(
              name,
              policy,
              (step, inputs) -> {
                onStep.accept(name, step);
                long next = 31L * (number + 1);
                for (final Map.Entry<String, Long> entry : inputs.entrySet()) {
                  next += (entry.getKey().charAt(0) - 'A' + 1L) * entry.getValue();
                }
                next %= MODULUS;
                values.put(name, new Value(step, next));
                if (step == steps && completed.incrementAndGet() == NODE_COUNT) {
                  limitReached.complete(null);
                }
                return next;
              },
              failure ->
                  limitReached.completeExceptionally(
                      new IllegalStateException(failure.toString()))));
    }
    for (int target = 0; target < NODE_COUNT; target++) {
      for (int offset = 1; offset <= 2 + target % 4; offset++) {
        final JoinTask<Long, Long, Long> publisher =
            workers.get(name(Math.floorMod(target - offset, NODE_COUNT)));
        publisher.relay(
            workers.get(name(target)),
            envelope -> envelope.requestId() + 1,
            envelope -> running.get() && envelope.requestId() < steps);
      }
    }
    input =
        new PublishingTask<>("control", Task.DEFAULT_MAILBOX_CAPACITY) {
          private boolean started;

          @Override
          protected void process(final Boolean start) {
            if (start) {
              if (!started) {
                started = true;
                running.set(true);
                seed();
              }
            } else {
              running.set(false);
              publishAdvisory(TaskCommand.STOP);
              stop();
            }
          }

          @Override
          protected void onAdvisory(final Object message) {
            if (message == Control.KILL_ALL) {
              running.set(false);
              publishAdvisory(TaskCommand.KILL);
              advise(TaskCommand.KILL);
            }
          }
        };
    for (final Task<?> worker : workers.values()) {
      input.relayAdvisories(worker);
    }
    agent =
        Agent.builder()
            .tasks(new ArrayList<Task<?>>(workers.values()))
            .parallelism(parallelism)
            .build();
  }

  /** Starts the runtime; values do not flow until the Boolean input receives true. */
  public void start() {
    agent.start();
    // The control thread stays responsive even if all computation threads are occupied.
    input.start();
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The task is the public, thread-safe control endpoint of this demo")
  public Task<Boolean> input() {
    return input;
  }

  private void seed() {
    // Bootstrap each feedback edge with its publisher's step-zero value. Subsequent envelopes
    // are produced automatically by the normal named-publisher subscriptions.
    for (final JoinTask<Long, Long, Long> target : workers.values()) {
      for (final String publisherName : target.publisherNames()) {
        final Task<?> publisher = workers.get(publisherName);
        final long initial = publisherName.charAt(0) - 'A' + 1L;
        target.accept(new JoinInput<>(1L, publisher.id(), publisherName, initial));
      }
    }
  }

  public void awaitStepLimit(final Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    limitReached.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  /** Waits for advisory shutdown, then closes the worker pool; never blocks a worker thread. */
  public boolean awaitStopped(final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    final List<Task<?>> all = new ArrayList<>(workers.values());
    all.add(input);
    for (final Task<?> task : all) {
      if (!task.awaitStopped(remaining(deadline))) {
        return false;
      }
    }
    agent.stop();
    return agent.awaitStopped(remaining(deadline));
  }

  private static Duration remaining(final long deadline) {
    return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
  }

  /**
   * Inspect after awaitStopped for a stable snapshot. During execution, nodes may differ in step.
   */
  public Map<String, NodeState> snapshot() {
    final Map<String, NodeState> snapshot = new LinkedHashMap<>();
    for (final Map.Entry<String, JoinTask<Long, Long, Long>> entry : workers.entrySet()) {
      final Value value = values.get(entry.getKey());
      final Task<?> task = entry.getValue();
      snapshot.put(
          entry.getKey(),
          new NodeState(
              value.step(),
              value.value(),
              task.state(),
              task.inboxSize(),
              task.advisoryInboxSize()));
    }
    return Map.copyOf(snapshot);
  }

  public Map<String, Integer> dependencyCounts() {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (final Map.Entry<String, JoinTask<Long, Long, Long>> entry : workers.entrySet()) {
      counts.put(entry.getKey(), entry.getValue().publisherNames().size());
    }
    return Map.copyOf(counts);
  }

  @Override
  public void close() throws InterruptedException {
    running.set(false);
    input.stop();
    agent.stop();
    requireStopped();
  }

  private void requireStopped() throws InterruptedException {
    if (!awaitStopped(shutdownTimeout)) {
      throw new IllegalStateException("Loop workers are still stopping");
    }
  }

  private static String name(final int index) {
    return String.valueOf((char) ('A' + index));
  }

  private record Value(long step, long value) {}

  /**
   * One worker's observable state.
   *
   * @param step last completed logical step
   * @param value current value
   * @param state task lifecycle state
   * @param queuedData retained data messages
   * @param queuedAdvisories retained optional messages
   */
  public record NodeState(
      long step, long value, TaskState state, int queuedData, int queuedAdvisories) {}

  public static void main(final String[] args) throws Exception {
    final int steps = args.length == 0 ? 100 : Integer.parseInt(args[0]);
    try (LoopDemo loop = new LoopDemo(4, steps)) {
      loop.start();
      loop.input().accept(true);
      loop.awaitStepLimit(WAIT);
      loop.input().accept(false);
      loop.requireStopped();
      loop.snapshot().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> System.out.println(entry.getKey() + " " + entry.getValue()));
    }
  }
}
