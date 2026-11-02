package com.cynthiasystems.agentflow.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.Agent;
import com.cynthiasystems.agentflow.tasks.JoinPolicy;
import com.cynthiasystems.agentflow.tasks.Task;

public class DiamondDemoTest {
  @Test
  public void diamondJoinsBothBranchesUsingOnlyOneWorker() throws Exception {
    assertEquals(
        Map.of("request-1", 35, "request-2", 55),
        DiamondDemo.run(1).stream()
            .collect(Collectors.toMap(DiamondDemo.Result::requestId, DiamondDemo.Result::value)));
  }

  @Test
  public void diamondAlsoWorksWithConcurrentBranches() throws Exception {
    assertEquals(
        Map.of("request-1", 35, "request-2", 55),
        DiamondDemo.run(4).stream()
            .collect(Collectors.toMap(DiamondDemo.Result::requestId, DiamondDemo.Result::value)));
  }

  @Test
  public void commandLineDemoPrintsBothResultsWithDefaultAndExplicitWorkerCounts()
      throws Exception {
    final PrintStream original = System.out;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      DiamondDemo.main(new String[0]);
      DiamondDemo.main(new String[] {"4"});
    } finally {
      System.setOut(original);
    }
    final String oneRun =
        "request-1 -> 35" + System.lineSeparator() + "request-2 -> 55" + System.lineSeparator();
    assertEquals(oneRun + oneRun, bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void demoReportsJoinRejectionsInsteadOfHangingForMissingResults() {
    final JoinPolicy limited = new JoinPolicy(Duration.ofSeconds(30), 1, 8, 16);
    final ExecutionException error =
        assertThrows(ExecutionException.class, () -> DiamondDemo.run(1, limited));
    assertTrue(error.getCause().getMessage().contains("CAPACITY"));
  }

  @Test
  public void demoReportsShutdownTimeoutWhenUserInitializationDoesNotReturn() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final Task<Integer> blocked =
        new Task<>("blocked") {
          @Override
          protected void initialize() {
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
          }

          @Override
          protected void process(final Integer input) {}
        };
    final Agent agent = Agent.builder().task(blocked).build();
    try {
      final IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  DiamondDemo.runGraph(
                      agent, CompletableFuture.completedFuture(List.of()), Duration.ofMillis(5)));
      assertTrue(error.getMessage().contains("still stopping"));
    } finally {
      release.countDown();
      agent.stop();
      assertTrue(agent.awaitStopped(Duration.ofSeconds(5)));
    }
  }
}
