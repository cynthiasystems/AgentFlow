package com.cynthiasystems.agentflow.tasks;

import lombok.experimental.NonFinal;

public abstract class AdaptiveTimingTask extends Task {
  double alpha = 0.1;
  @NonFinal double estimatedSleepTime = 100;

  @Override
  protected long calculateSleepTime() {
    if (lastWaitingTime() == 0) {
      return (long) estimatedSleepTime;
    }
    estimatedSleepTime = alpha * lastWaitingTime() + (1 - alpha) * estimatedSleepTime;
    return (long) estimatedSleepTime;
  }
}
