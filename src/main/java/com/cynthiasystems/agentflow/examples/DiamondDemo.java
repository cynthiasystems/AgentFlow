package com.cynthiasystems.agentflow.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.cynthiasystems.agentflow.Agent;
import com.cynthiasystems.agentflow.tasks.JoinPolicy;
import com.cynthiasystems.agentflow.tasks.JoinTask;
import com.cynthiasystems.agentflow.tasks.RelayTask;
import com.cynthiasystems.agentflow.tasks.Task;

/** Runnable diamond: source -> double/triple branches -> keyed sum -> output. */
public final class DiamondDemo {
  private DiamondDemo() {}

  public static void main(final String[] args)
      throws InterruptedException, ExecutionException, TimeoutException {
    final int workers = args.length == 0 ? 1 : Integer.parseInt(args[0]);
    for (final Result result : run(workers)) {
      System.out.println(result.requestId() + " -> " + result.value());
    }
  }

  /** Runs the same graph with one or more shared workers and returns both joined results. */
  // The graph is intentionally assembled using the public factories and fluent builder API.
  @SuppressWarnings("PMD.LawOfDemeter")
  public static List<Result> run(final int workers)
      throws InterruptedException, ExecutionException, TimeoutException {
    return run(workers, JoinPolicy.defaults());
  }

  @SuppressWarnings("PMD.LawOfDemeter")
  static List<Result> run(final int workers, final JoinPolicy policy)
      throws InterruptedException, ExecutionException, TimeoutException {
    final CompletableFuture<List<Result>> completed = new CompletableFuture<>();
    final List<Result> results = new ArrayList<>();
    final List<Request> requests =
        List.of(new Request("request-1", 7), new Request("request-2", 11));
    final RelayTask<Request, Request> source = RelayTask.of("source", request -> request);
    final RelayTask<Request, Integer> left =
        RelayTask.of("doubleTask", request -> request.value() * 2);
    final RelayTask<Request, Integer> right =
        RelayTask.of("tripleTask", request -> request.value() * 3);
    final JoinTask<String, Integer, Result> join =
        new JoinTask<>(
            "sum",
            policy,
            (id, inputs) -> new Result(id, inputs.get("doubleTask") + inputs.get("tripleTask")),
            failure ->
                completed.completeExceptionally(new IllegalStateException(failure.toString())));
    final Task<Result> output =
        new Task<>("output") {
          @Override
          protected void process(final Result result) {
            results.add(result);
            if (results.size() == requests.size()) {
              completed.complete(List.copyOf(results));
            }
          }
        };
    source.relay(left);
    source.relay(right);
    left.relay(join, Request::id);
    right.relay(join, Request::id);
    join.relay(output);
    final Agent agent =
        Agent.builder()
            .task(source)
            .task(left)
            .task(right)
            .task(join)
            .task(output)
            .parallelism(workers)
            .build();
    for (final Request request : requests) {
      source.accept(request);
    }
    return runGraph(agent, completed, Duration.ofSeconds(5));
  }

  static List<Result> runGraph(
      final Agent agent,
      final CompletableFuture<List<Result>> completed,
      final Duration shutdownTimeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    final List<Result> joined;
    final boolean stopped;
    try {
      agent.start();
      joined = completed.get(5, TimeUnit.SECONDS);
    } finally {
      agent.stop();
      stopped = agent.awaitStopped(shutdownTimeout);
    }
    if (!stopped) {
      throw new IllegalStateException("Diamond demo is still stopping");
    }
    return joined;
  }

  private record Request(String id, int value) {}

  /**
   * The combined output, retaining the correlation ID.
   *
   * @param requestId original request ID
   * @param value sum of the branch results
   */
  public record Result(String requestId, int value) {}
}
