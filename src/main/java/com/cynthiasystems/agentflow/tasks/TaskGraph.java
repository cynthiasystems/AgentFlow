package com.cynthiasystems.agentflow.tasks;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Serializes graph configuration and freezes complete connected components before execution. */
// Graph state is owned by this class even when reached through a task's graph field.
@SuppressWarnings("PMD.LawOfDemeter")
final class TaskGraph {
  static final Object LOCK = new Object();
  private final Set<Task<?>> neighbors = new HashSet<>();
  private boolean frozen;

  static void freeze(final Collection<? extends Task<?>> roots) {
    synchronized (LOCK) {
      final Set<Task<?>> graph = new HashSet<>();
      final Deque<Task<?>> remaining = new ArrayDeque<>(roots);
      while (!remaining.isEmpty()) {
        final Task<?> task = remaining.removeFirst();
        if (graph.add(task)) {
          remaining.addAll(task.graph.neighbors);
        }
      }
      final Set<String> names = new HashSet<>();
      for (final Task<?> task : graph) {
        if (!names.add(task.name())) {
          throw new IllegalArgumentException("Duplicate task name: " + task.name());
        }
        task.validateConfiguration();
      }
      for (final Task<?> task : graph) {
        task.graph.frozen = true;
      }
    }
  }

  static void freezeIfNeeded(final Task<?> task) {
    synchronized (LOCK) {
      if (!task.graph.frozen) {
        freeze(List.of(task));
      }
    }
  }

  static void connect(final Task<?> source, final Task<?> target, final Runnable connection) {
    Objects.requireNonNull(target, "target");
    synchronized (LOCK) {
      if (source.graph.frozen || target.graph.frozen) {
        throw new IllegalStateException("Subscriptions are frozen after graph startup");
      }
      connection.run();
      source.graph.neighbors.add(target);
      target.graph.neighbors.add(source);
    }
  }
}
