package com.cynthiasystems.agentflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.tasks.Task;
import com.cynthiasystems.agentflow.tasks.examples.CounterTask;

import lombok.SneakyThrows;

class AgentTest {

  @Test
  @SneakyThrows
  void testAgentStartsAndStopsAllTasks() {
    // Create test tasks
    CounterTask task1 = new CounterTask();
    CounterTask task2 = new CounterTask();
    CounterTask task3 = new CounterTask();

    // Build agent with tasks
    Agent agent = Agent.builder().task(task1).task(task2).task(task3).build();

    // Verify initial state
    assertFalse(task1.isThreadAlive());
    assertFalse(task2.isThreadAlive());
    assertFalse(task3.isThreadAlive());

    // Start the agent
    agent.start();

    // Verify all tasks are started
    assertTrue(task1.isThreadAlive());
    assertTrue(task2.isThreadAlive());
    assertTrue(task3.isThreadAlive());

    // Let tasks run briefly
    Thread.sleep(50);

    // Verify tasks are processing
    assertTrue(task1.processCount().get() > 0);
    assertTrue(task2.processCount().get() > 0);
    assertTrue(task3.processCount().get() > 0);

    // Stop the agent
    agent.stop();
    Thread.sleep(100);

    // Verify all tasks are stopped
    assertFalse(task1.isThreadAlive());
    assertFalse(task2.isThreadAlive());
    assertFalse(task3.isThreadAlive());

    // Verify lifecycle hooks were called
    assertEquals(1, task1.beforeStartCount().get());
    assertEquals(1, task1.afterStopCount().get());
    assertEquals(1, task2.beforeStartCount().get());
    assertEquals(1, task2.afterStopCount().get());
    assertEquals(1, task3.beforeStartCount().get());
    assertEquals(1, task3.afterStopCount().get());
  }

  @Test
  void testEmptyAgent() {
    final Agent agent = Agent.builder().build();
    agent.start();
    agent.stop();
  }

  @Test
  @SneakyThrows
  void testAgentWithTaskList() {
    // Test the tasks() method that takes a list instead of individual task() calls
    CounterTask task1 = new CounterTask();
    CounterTask task2 = new CounterTask();

    // Build using list
    Agent agent = Agent.builder().tasks(java.util.Arrays.asList(task1, task2)).build();

    // Start and verify
    agent.start();
    assertTrue(task1.isThreadAlive());
    assertTrue(task2.isThreadAlive());

    // Stop and verify
    agent.stop();
    Thread.sleep(100);
    assertFalse(task1.isThreadAlive());
    assertFalse(task2.isThreadAlive());
  }

  @Test
  @SneakyThrows
  void testMultipleStartStop() {
    // Create test task
    CounterTask task = new CounterTask();

    // Build agent
    Agent agent = Agent.builder().task(task).build();

    // First cycle
    agent.start();
    assertTrue(task.isThreadAlive());
    agent.stop();
    waitForThreadsToTerminate(new Task[] {task}, 1000);
    assertFalse(task.isThreadAlive());
    assertEquals(1, task.beforeStartCount().get());
    assertEquals(1, task.afterStopCount().get());

    // Second cycle - ensure complete cleanup before starting again
    Thread.sleep(50); // Give a little extra time for cleanup
    agent.start();
    assertTrue(task.isThreadAlive());
    agent.stop();
    waitForThreadsToTerminate(new Task[] {task}, 1000);
    assertFalse(task.isThreadAlive());
    assertEquals(2, task.beforeStartCount().get());
    assertEquals(2, task.afterStopCount().get());
  }

  /**
   * Helper method to wait for threads to terminate with a timeout. This is more robust than a
   * simple sleep.
   */
  @SneakyThrows
  private void waitForThreadsToTerminate(Task[] tasks, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    boolean allTerminated = false;

    while (!allTerminated && System.currentTimeMillis() < deadline) {
      allTerminated = true;
      for (Task task : tasks) {
        if (task.isThreadAlive()) {
          allTerminated = false;
          Thread.sleep(10); // Small sleep between checks
          break;
        }
      }
    }
  }
}
