package com.cynthiasystems.agentflow.tasks;

/** Reserved advisory commands. Interruption is cooperative; KILL never uses Thread.stop(). */
public enum TaskCommand {
  STOP,
  KILL
}
