package com.cynthiasystems.agentflow.tasks.examples;

import com.cynthiasystems.agentflow.tasks.Task;

import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

@Accessors(fluent = true)
@Setter
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class TestTrueTask extends Task {
  @Override
  protected boolean shouldProcess() {
    return true;
  }

  @Override
  protected void process() {
    result = true;
  }

  @Override
  protected long calculateSleepTime() {
    return 10;
  }
}
