package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.tasks.examples.AdaptiveTask;

import lombok.SneakyThrows;

public class AdaptiveTimingTaskTest {

  @Test
  @SneakyThrows
  public void testInitialSleepTime() {
    final AdaptiveTask task = new AdaptiveTask();
    assertEquals(100, task.estimatedSleepTime(), "Initial sleep time should be 100ms");
    task.start();
    Thread.sleep(100);
    task.stop();
    assertEquals(100, task.calculateSleepTime(), "Should return default sleep time initially");
  }

  @Test
  @SneakyThrows
  public void testAdaptiveAlgorithm() {
    final AdaptiveTask task = new AdaptiveTask();
    task.start();
    Thread.sleep(100);
    task.shouldProcessFlag(false);
    Thread.sleep(200);
    task.shouldProcessFlag(true);
    Thread.sleep(100);
    long sleepTime = task.calculateSleepTime();
    assertTrue(
        100 < sleepTime && sleepTime < 200, "Sleep time should adapt according to alpha formula");
    task.stop();
  }
}
