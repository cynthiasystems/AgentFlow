package com.cynthiasystems.agentflow.tasks;

import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.NonFinal;

@Accessors(fluent = true, chain = true)
public abstract class AdaptiveTimingTask extends Task {
  double alpha = 0.1;

  @NonFinal @Getter double estimatedSleepTime = 100;

  @Override
  protected long calculateSleepTime() {
    if (lastWaitingTime() == 0) {
      return (long) estimatedSleepTime;
    }
    estimatedSleepTime = alpha * lastWaitingTime() + (1 - alpha) * estimatedSleepTime;
    return (long) estimatedSleepTime;
  }
}
