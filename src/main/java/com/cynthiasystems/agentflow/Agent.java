package com.cynthiasystems.agentflow;

import java.util.List;

import com.cynthiasystems.agentflow.tasks.Task;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Singular;
import lombok.Synchronized;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

@Accessors(fluent = true, chain = true)
@Builder(access = AccessLevel.PUBLIC)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class Agent {
  @Singular List<Task> tasks;

  @Synchronized
  public void start() {
    for (final Task task : tasks) {
      task.start();
    }
  }

  @Synchronized
  public void stop() {
    for (final Task task : tasks) {
      task.stop();
    }
  }
}
