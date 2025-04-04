package com.cynthiasystems.agentflow.tasks;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.cynthiasystems.agentflow.tasks.examples.CounterTask;
import com.cynthiasystems.agentflow.tasks.examples.TrueTask;

import lombok.SneakyThrows;

public class TaskTest {

  @SneakyThrows
  @Test
  public void taskStartsAndCreatesThread() {
    final Task task = new TrueTask();
    assertFalse(task.isThreadAlive());
    assertNull(task.getThreadName());
    task.start();
    assertTrue(task.isThreadAlive());
    assertEquals(task.id(), task.getThreadName());
    task.stop();
    Thread.sleep(100);
    assertFalse(task.isThreadAlive());
  }

  @SneakyThrows
  @Test
  public void testLifecycleAndProcessing() {
    // Initialize task with a process limit
    final CounterTask task =
        new CounterTask()
            .processLimit(5) // Process only 5 times
            .sleepTime(10); // Sleep 10ms between iterations

    // Verify initial state
    assertEquals(0, task.beforeStartCount().get());
    assertEquals(0, task.initializeCount().get());
    assertEquals(0, task.processCount().get());

    // Start the task and verify hooks are called
    task.start();
    assertEquals(1, task.beforeStartCount().get());

    // Wait for processing to complete (limited to 5)
    Thread.sleep(200); // Should be enough time for 5 processes with 10ms sleep

    // Verify processing happened but stopped at limit
    assertEquals(5, task.processCount().get());
    assertEquals(1, task.initializeCount().get());

    // Check waiting time behavior - should be non-zero now that we hit the limit
    // and processing is no longer happening
    Thread.sleep(50); // Give time for waiting time to accumulate
    assertTrue(task.lastWaitingTime() > 0);

    // Test changing behavior mid-execution
    task.shouldProcess(true); // Make it process again (redundant here but tests setter)
    task.processLimit(8); // Set a new limit to allow more processing
    Thread.sleep(100); // Give time for more processing

    // Should have processed more times
    assertTrue(task.processCount().get() > 5);

    // Test that direct run() call throws exception
    final CounterTask illegalTask = new CounterTask();
    Exception exception = assertThrows(IllegalStateException.class, illegalTask::run);
    assertTrue(exception.getMessage().contains("start()"));

    // Test stopping the task
    assertEquals(0, task.afterStopCount().get());
    assertEquals(0, task.cleanupCount().get());
    task.stop();

    // Verify stop hooks were called
    assertEquals(1, task.afterStopCount().get());

    // Wait for thread to terminate
    Thread.sleep(100);
    assertFalse(task.isThreadAlive());

    // Verify cleanup was called after thread terminated
    assertEquals(1, task.cleanupCount().get());

    // Verify starting an already-stopped task works
    task.start();
    assertTrue(task.isThreadAlive());
    assertEquals(2, task.beforeStartCount().get());
    task.stop();
    Thread.sleep(50);
    assertEquals(2, task.afterStopCount().get());
  }
}
