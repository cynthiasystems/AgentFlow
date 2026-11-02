package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.Agent;

public class NamedPublishingTest {
  @Test
  public void publisherIdentityIsAutomaticAndInputsStayGroupedByRequest() {
    final ManualExecutor executor = new ManualExecutor();
    final List<Map<String, Integer>> results = new ArrayList<>();
    final List<String> ids = new ArrayList<>();
    final JoinTask<String, Integer, Integer> join =
        new JoinTask<>(
            "sum",
            JoinPolicy.defaults(),
            (id, inputs) -> {
              ids.add(id);
              results.add(inputs);
              return 0;
            },
            failure -> {
              throw new AssertionError(failure);
            });
    final RelayTask<Request, Integer> doubleTask = RelayTask.of("doubleTask", r -> r.value() * 2);
    final RelayTask<Request, Integer> tripleTask = RelayTask.of("tripleTask", r -> r.value() * 3);
    doubleTask.relay(join, Request::id);
    tripleTask.relay(join, Request::id);
    assertEquals(Set.of("doubleTask", "tripleTask"), join.publisherNames());
    assertThrows(UnsupportedOperationException.class, () -> join.publisherNames().clear());
    assertNotEquals(doubleTask.id(), tripleTask.id());
    doubleTask.start(executor);
    tripleTask.start(executor);
    join.start(executor);
    drain(executor);
    tripleTask.accept(new Request("b", 11));
    doubleTask.accept(new Request("a", 7));
    drain(executor);
    assertTrue(results.isEmpty());
    tripleTask.accept(new Request("a", 7));
    drain(executor);
    doubleTask.accept(new Request("b", 11));
    drain(executor);
    assertEquals(List.of("a", "b"), ids);
    assertEquals(
        List.of(
            Map.of("doubleTask", 14, "tripleTask", 21), Map.of("doubleTask", 22, "tripleTask", 33)),
        results);
    doubleTask.stop();
    tripleTask.stop();
    join.stop();
    drain(executor);
  }

  @Test
  public void joinCanPublishToAnotherJoinAndKeepCorrelation() {
    final ManualExecutor executor = new ManualExecutor();
    final List<String> results = new ArrayList<>();
    final JoinTask<String, Integer, Integer> sum =
        new JoinTask<>(
            "sum", JoinPolicy.defaults(), (id, inputs) -> inputs.get("source") + 1, failure -> {});
    final JoinTask<String, Integer, String> next =
        new JoinTask<>(
            "next",
            JoinPolicy.defaults(),
            (id, inputs) -> id + ":" + inputs.get("sum"),
            failure -> {});
    final RelayTask<Request, Integer> source = RelayTask.of("source", Request::value);
    final RelayTask<String, Boolean> sink = RelayTask.of("sink", results::add);
    source.relay(sum, Request::id);
    sum.relay(next, JoinInput::requestId);
    next.relay(sink);
    final List<Task<?>> tasks = List.of(source, sum, next, sink);
    for (final Task<?> task : tasks) {
      task.start(executor);
    }
    source.accept(new Request("request-42", 10));
    drain(executor);
    assertEquals(List.of("request-42:11"), results);
    assertEquals(Set.of("sum"), next.publisherNames());
    for (final Task<?> task : tasks) {
      task.stop();
    }
    drain(executor);
  }

  @Test
  public void duplicateSubscriptionsAndPublisherNamesAreRejectedWithoutChangingTheJoin() {
    final JoinTask<String, Integer, Integer> join = join("sum");
    final RelayTask<Request, Integer> first = RelayTask.of("publisher", Request::value);
    final RelayTask<Request, Integer> duplicateName = RelayTask.of("publisher", Request::value);
    first.relay(join, Request::id);
    assertThrows(IllegalArgumentException.class, () -> first.relay(join, Request::id));
    assertThrows(IllegalArgumentException.class, () -> duplicateName.relay(join, Request::id));
    assertEquals(Set.of("publisher"), join.publisherNames());
    assertThrows(NullPointerException.class, () -> first.relay(join, null));
    assertThrows(NullPointerException.class, () -> first.relay(null, Request::id));
    final RelayTask<Integer, Integer> plain = RelayTask.of("plain", x -> x);
    final RelayTask<Integer, Integer> sink = RelayTask.of("sink", x -> x);
    plain.relay(sink);
    assertThrows(IllegalArgumentException.class, () -> plain.relay(sink));
  }

  @Test
  public void startingAnyNodeFreezesAllSubscriptionsEvenAcrossRestart() {
    final ManualExecutor executor = new ManualExecutor();
    final JoinTask<String, Integer, Integer> join = join("sum");
    final RelayTask<Request, Integer> source = RelayTask.of("source", Request::value);
    source.relay(join, Request::id);
    source.start(executor);
    assertThrows(IllegalStateException.class, () -> source.relay(RelayTask.of(x -> x)));
    final RelayTask<Request, Integer> late = RelayTask.of("late", Request::value);
    assertThrows(IllegalStateException.class, () -> late.relay(join, Request::id));
    assertEquals(Set.of("source"), join.publisherNames());
    source.stop();
    drain(executor);
    assertThrows(IllegalStateException.class, () -> late.relay(join, Request::id));
    source.start(executor);
    source.stop();
    drain(executor);
  }

  @Test
  public void duplicateGraphNamesFailBeforeAnyWorkerStartsAndInvalidGraphCanBeRepaired() {
    final RelayTask<Integer, Integer> first = RelayTask.of("same", x -> x);
    final RelayTask<Integer, Integer> second = RelayTask.of("same", x -> x);
    final Agent agent = Agent.builder().task(first).task(second).build();
    assertThrows(IllegalArgumentException.class, agent::start);
    assertEquals(TaskState.CREATED, first.state());
    assertEquals(TaskState.CREATED, second.state());
    final JoinTask<String, Integer, Integer> join = join("sum");
    assertThrows(IllegalStateException.class, join::start);
    final RelayTask<Request, Integer> source = RelayTask.of("source", Request::value);
    source.relay(join, Request::id);
    final ManualExecutor executor = new ManualExecutor();
    join.start(executor);
    join.stop();
    drain(executor);
  }

  @Test
  public void matchingPublisherNameWithWrongInstanceIdCannotSatisfyAJoin() {
    final JoinTask<String, Integer, Integer> join = join("sum");
    final RelayTask<Request, Integer> source = RelayTask.of("source", Request::value);
    source.relay(join, Request::id);
    final ManualExecutor executor = new ManualExecutor();
    join.accept(new JoinInput<>("a", "wrong-instance-id", "source", 1));
    join.start(executor);
    drain(executor);
    assertEquals(TaskState.FAILED, join.state());
    assertTrue(join.failure() instanceof IllegalArgumentException);
  }

  @Test
  public void namesAreImmutableValidatedAndAvailableOnEveryTask() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> RelayTask.of(" ", x -> x));
    assertThrows(NullPointerException.class, () -> RelayTask.of(null, x -> x));
    final Task<Integer> task =
        new Task<>("consumer") {
          @Override
          protected void process(final Integer message) {}
        };
    assertEquals("consumer", task.name());
    final String id = task.id();
    task.start();
    task.start();
    task.stop();
    assertTrue(task.awaitStopped(Duration.ofSeconds(5)));
    assertEquals(id, task.id());
    assertFalse(RelayTask.of(x -> x).name().isBlank());
  }

  private static JoinTask<String, Integer, Integer> join(final String name) {
    return new JoinTask<>(name, JoinPolicy.defaults(), (id, inputs) -> 0, failure -> {});
  }

  private static void drain(final ManualExecutor executor) {
    while (executor.pending() > 0) {
      executor.runNext();
    }
  }

  private record Request(String id, int value) {}
}
