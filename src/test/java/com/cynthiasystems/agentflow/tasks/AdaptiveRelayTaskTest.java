package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.Agent;

import lombok.SneakyThrows;

public class AdaptiveRelayTaskTest {

  @Test
  @SneakyThrows
  public void testCyclicRelayChain() {
    final int[] invocationCountA = new int[] {0};
    final int[] invocationCountB = new int[] {0};

    final AdaptiveRelayTask<Double, Double> halfTaskA =
        AdaptiveRelayTask.of(
            x -> {
              invocationCountA[0]++;
              return x / 2;
            });
    final AdaptiveRelayTask<Double, Double> halfTaskB =
        AdaptiveRelayTask.of(
            x -> {
              invocationCountB[0]++;
              return x / 2;
            });

    halfTaskA.accept(1.0);

    // Connect tasks in a cycle
    halfTaskA.relay(halfTaskB);
    halfTaskB.relay(halfTaskA);

    // Start tasks in an agent
    final Agent agent = Agent.builder().task(halfTaskA).task(halfTaskB).build();

    agent.start();

    Thread.sleep(1000);

    agent.stop();

    final int totalInvocationCount = invocationCountA[0] + invocationCountB[0];

    if (halfTaskA.inboxSize() > 0) {
      assertEquals(Math.pow(2, -1 * totalInvocationCount), (double) halfTaskA.inboxEntry(0));
    }

    if (halfTaskB.inboxSize() > 0) {
      assertEquals(Math.pow(2, -1 * totalInvocationCount), (double) halfTaskB.inboxEntry(0));
    }
  }
}
