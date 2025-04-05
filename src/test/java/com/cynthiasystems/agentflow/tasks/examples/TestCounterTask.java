package com.cynthiasystems.agentflow.tasks.examples;

import java.util.concurrent.atomic.AtomicInteger;

import com.cynthiasystems.agentflow.tasks.Task;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

@Accessors(fluent = true, chain = true)
@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TestCounterTask extends Task {
  // Counters to track method invocations
  final AtomicInteger processCount = new AtomicInteger(0);
  final AtomicInteger initializeCount = new AtomicInteger(0);
  final AtomicInteger cleanupCount = new AtomicInteger(0);
  final AtomicInteger beforeStartCount = new AtomicInteger(0);
  final AtomicInteger afterStopCount = new AtomicInteger(0);

  // Control behavior
  @NonFinal @Setter volatile boolean shouldProcess = true;
  @NonFinal @Setter volatile long sleepTime = 10;
  @NonFinal @Setter int processLimit = -1; // Process forever if negative

  @Override
  protected boolean shouldProcess() {
    return shouldProcess && (processLimit < 0 || processCount.get() < processLimit);
  }

  @Override
  protected void process() {
    processCount.incrementAndGet();
  }

  @Override
  protected long calculateSleepTime() {
    return sleepTime;
  }

  @Override
  protected void initialize() {
    super.initialize();
    initializeCount.incrementAndGet();
  }

  @Override
  protected void cleanup() {
    super.cleanup();
    cleanupCount.incrementAndGet();
  }

  @Override
  protected void beforeStart() {
    super.beforeStart();
    beforeStartCount.incrementAndGet();
  }

  @Override
  protected void afterStop() {
    super.afterStop();
    afterStopCount.incrementAndGet();
  }
}
