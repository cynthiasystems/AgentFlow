package com.cynthiasystems.agentflow.tasks.examples;

import com.cynthiasystems.agentflow.tasks.AdaptiveTimingTask;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

/**
 * Test implementation of AdaptiveTimingTask that allows controlling its behavior and accessing
 * internal state for verification.
 */
@Accessors(fluent = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Getter
public class AdaptiveTask extends AdaptiveTimingTask {
  @Setter @NonFinal boolean shouldProcessFlag = true;
  @NonFinal int processCount = 0;

  @Override
  protected boolean shouldProcess() {
    return shouldProcessFlag;
  }

  @Override
  protected void process() {
    processCount++;
  }
}
