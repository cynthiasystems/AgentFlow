package com.cynthiasystems.agentflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.tasks.ManualExecutor;
import com.cynthiasystems.agentflow.tasks.RelayTask;
import com.cynthiasystems.agentflow.tasks.Task;
import com.cynthiasystems.agentflow.tasks.TaskState;

public class AgentTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  public void agentStartsStopsAndRestartsAllTasks() throws Exception {
    final AtomicInteger total = new AtomicInteger();
    final Semaphore delivered = new Semaphore(0);
    final Function<Integer, Integer> increment =
        value -> {
          total.addAndGet(value);
          delivered.release();
          return value;
        };
    final Task<Integer> first = RelayTask.of(increment);
    final Task<Integer> second = RelayTask.of(increment);
    final Agent agent = Agent.builder().tasks(List.of(first, second)).parallelism(1).build();
    try {
      first.accept(1);
      second.accept(2);
      agent.start();
      agent.start();
      assertTrue(delivered.tryAcquire(2, 5, TimeUnit.SECONDS));
      assertEquals(3, total.get());
      assertEquals(TaskState.STARTED, first.state());
      assertEquals(TaskState.STARTED, second.state());
      agent.stop();
      assertTrue(agent.awaitStopped(TIMEOUT));
      assertEquals(TaskState.STOPPED, first.state());
      assertEquals(TaskState.STOPPED, second.state());
      agent.stop();
      first.accept(3);
      second.accept(4);
      agent.start();
      assertTrue(delivered.tryAcquire(2, 5, TimeUnit.SECONDS));
      assertEquals(10, total.get());
      assertEquals(TaskState.STARTED, first.state());
    } finally {
      agent.stop();
      assertTrue(agent.awaitStopped(TIMEOUT));
    }
  }

  @Test
  public void aSingleWorkerCanRunAnEntirePipeline() throws Exception {
    final CountDownLatch delivered = new CountDownLatch(1);
    final AtomicInteger result = new AtomicInteger();
    final RelayTask<Integer, Integer> first = RelayTask.of(x -> x * 2);
    final RelayTask<Integer, Integer> second = RelayTask.of(x -> x + 1);
    final Task<Integer> sink =
        new Task<>() {
          @Override
          protected void process(final Integer message) {
            result.set(message);
            delivered.countDown();
          }
        };
    first.relay(second);
    second.relay(sink);
    first.accept(20);
    final Agent agent = Agent.builder().task(first).task(second).task(sink).parallelism(1).build();
    try {
      agent.start();
      assertTrue(delivered.await(5, TimeUnit.SECONDS));
      assertEquals(41, result.get());
    } finally {
      agent.stop();
      assertTrue(agent.awaitStopped(TIMEOUT));
    }
  }

  @Test
  public void shutdownSignalsAllTasksBeforeWaitingAndPreventsEarlyRestart() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Task<Integer> slow =
        new Task<>() {
          @Override
          protected void process(final Integer message) throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
          }
        };
    final Task<Integer> other = RelayTask.of(x -> x);
    final Agent agent = Agent.builder().task(slow).task(other).parallelism(1).build();
    slow.accept(1);
    try {
      agent.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      agent.stop();
      assertEquals(TaskState.STOPPING, slow.state());
      assertEquals(TaskState.STOPPING, other.state());
      assertFalse(agent.awaitStopped(Duration.ZERO));
      assertThrows(IllegalStateException.class, agent::start);
    } finally {
      release.countDown();
      agent.stop();
      assertTrue(agent.awaitStopped(TIMEOUT));
    }
    assertEquals(TaskState.STOPPED, other.state());
  }

  @Test
  public void taskFailureDoesNotStopOtherTasks() throws Exception {
    final Task<Integer> broken =
        RelayTask.of(
            x -> {
              throw new IllegalArgumentException("bad input");
            });
    final CountDownLatch delivered = new CountDownLatch(1);
    final Task<Integer> healthy =
        RelayTask.of(
            x -> {
              delivered.countDown();
              return x;
            });
    final Agent agent = Agent.builder().task(broken).task(healthy).parallelism(1).build();
    broken.accept(1);
    healthy.accept(2);
    try {
      agent.start();
      assertTrue(delivered.await(5, TimeUnit.SECONDS));
      assertTrue(broken.awaitStopped(TIMEOUT));
      assertEquals(TaskState.FAILED, broken.state());
      assertEquals(TaskState.STARTED, healthy.state());
    } finally {
      agent.stop();
      assertTrue(agent.awaitStopped(TIMEOUT));
    }
  }

  @Test
  public void invalidConfigurationIsRejectedAndEmptyAgentsAreSafe() throws Exception {
    final Agent empty = Agent.builder().build();
    empty.stop();
    assertTrue(empty.awaitStopped(Duration.ZERO));
    empty.start();
    empty.stop();
    assertTrue(empty.awaitStopped(TIMEOUT));
    assertThrows(IllegalArgumentException.class, () -> Agent.builder().parallelism(-1).build());
    assertThrows(IllegalArgumentException.class, () -> empty.awaitStopped(Duration.ofSeconds(-1)));
    final Task<Integer> task = RelayTask.of(x -> x);
    assertThrows(
        IllegalArgumentException.class, () -> Agent.builder().task(task).task(task).build());
  }

  @Test
  public void failedStartRollsBackPreviouslyStartedTasks() throws Exception {
    final Task<Integer> owned = RelayTask.of(x -> x);
    final Task<Integer> first = RelayTask.of(x -> x);
    owned.start();
    final Agent agent = Agent.builder().task(first).task(owned).build();
    try {
      assertThrows(IllegalStateException.class, agent::start);
      assertTrue(first.awaitStopped(TIMEOUT));
      assertEquals(TaskState.STOPPED, first.state());
      assertEquals(TaskState.STARTED, owned.state());
    } finally {
      owned.stop();
      assertTrue(owned.awaitStopped(TIMEOUT));
      assertTrue(agent.awaitStopped(TIMEOUT));
    }
  }

  @Test
  public void builderSupportsClearingReusingAndEmptyCollections() throws Exception {
    final Task<Integer> task = RelayTask.of(x -> x);
    final Agent.AgentBuilder builder = Agent.builder();
    assertTrue(builder.toString().contains("AgentBuilder"));
    builder.clearTasks();
    builder.tasks(List.of());
    builder.tasks(List.of(task));
    builder.clearTasks();
    final Agent empty = builder.build();
    empty.start();
    empty.stop();
    assertTrue(empty.awaitStopped(TIMEOUT));
    builder.task(task);
    assertTrue(builder.toString().contains("tasks"));
    final Agent single = builder.build();
    single.start();
    single.stop();
    assertTrue(single.awaitStopped(TIMEOUT));
    assertThrows(NullPointerException.class, () -> Agent.builder().tasks(null));
  }

  @Test
  public void awaitStoppedWaitsForExecutorTerminationAfterTaskHooksFinish() throws Exception {
    final ManualExecutor executor = new ManualExecutor();
    final Task<Integer> task = RelayTask.of(x -> x);
    final Agent agent =
        new Agent(
            List.of(task),
            1,
            parallelism -> {
              assertEquals(1, parallelism);
              return executor;
            });
    agent.start();
    executor.runNext();
    task.stop();
    executor.runNext();
    assertTrue(task.awaitStopped(Duration.ZERO));
    // An executor may still be finishing queued work after the task's stop latch is released.
    final AtomicInteger completed = new AtomicInteger();
    executor.execute(completed::incrementAndGet);
    agent.stop();
    assertFalse(agent.awaitStopped(Duration.ZERO));
    executor.runNext();
    assertTrue(agent.awaitStopped(Duration.ZERO));
    assertEquals(1, completed.get());
  }
}
