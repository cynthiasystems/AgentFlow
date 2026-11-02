package com.cynthiasystems.agentflow.tasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Controlled monotonic time; firing a timer only signals the independently controlled worker. */
final class ManualJoinScheduler implements JoinScheduler {
  private final List<Alarm> alarms = new ArrayList<>();
  private long now;

  @Override
  public long nanoTime() {
    return now;
  }

  @Override
  public Cancellation schedule(final Runnable notification, final long delayNanos) {
    final Alarm alarm = new Alarm(notification, now, delayNanos);
    alarms.add(alarm);
    return () -> alarms.remove(alarm);
  }

  int activeAlarms() {
    return alarms.size();
  }

  Runnable nextNotification() {
    return alarms.get(0).notification();
  }

  void elapseWithoutFiring(final Duration duration) {
    now += duration.toNanos();
  }

  void advance(final Duration duration) {
    elapseWithoutFiring(duration);
    for (final Alarm alarm : new ArrayList<>(alarms)) {
      if (now - alarm.startedAt() >= alarm.delayNanos()) {
        alarms.remove(alarm);
        alarm.notification().run();
      }
    }
  }

  private record Alarm(Runnable notification, long startedAt, long delayNanos) {}
}
