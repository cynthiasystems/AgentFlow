package com.cynthiasystems.agentflow.tasks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Combines one value from every subscribed publisher, independently for each request ID.
 *
 * @param <K> request ID type
 * @param <V> branch value type
 * @param <R> joined output type
 */
// Collection views and Pending values belong to this worker; policy and inputs are value records.
@SuppressWarnings("PMD.LawOfDemeter")
public class JoinTask<K, V, R> extends PublishingTask<JoinInput<K, V>, R> {
  private final Map<String, String> publishers = new LinkedHashMap<>();
  private final JoinPolicy policy;
  private final BiFunction<K, Map<String, V>, R> combine;
  private final Consumer<JoinFailure<K>> onFailure;
  private final JoinScheduler scheduler;
  // These collections belong exclusively to the task worker, including during restart.
  private final Map<K, Pending<V>> pending = new LinkedHashMap<>();
  private final Set<K> finished = new LinkedHashSet<>();
  private long observedCancellations;
  private JoinScheduler.Cancellation alarm = () -> {};

  public JoinTask(
      final String name,
      final JoinPolicy policy,
      final BiFunction<K, Map<String, V>, R> combine,
      final Consumer<JoinFailure<K>> onFailure) {
    this(name, policy, combine, onFailure, SystemJoinScheduler.INSTANCE);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Construction validates configuration and acquires no privileged resources")
  JoinTask(
      final String name,
      final JoinPolicy policy,
      final BiFunction<K, Map<String, V>, R> combine,
      final Consumer<JoinFailure<K>> onFailure,
      final JoinScheduler scheduler) {
    super(name, policy.mailboxCapacity());
    this.policy = policy;
    this.combine = Objects.requireNonNull(combine, "combine");
    this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  void registerPublisher(final Task<?> publisher) {
    if (publishers.containsKey(publisher.name())) {
      throw new IllegalArgumentException("Duplicate publisher name: " + publisher.name());
    }
    publishers.put(publisher.name(), publisher.id());
  }

  void acceptPublication(final K requestId, final Task<?> publisher, final V value) {
    accept(new JoinInput<>(requestId, publisher.id(), publisher.name(), value));
  }

  /** Returns the configured publisher names; the set freezes when the graph starts. */
  public Set<String> publisherNames() {
    synchronized (TaskGraph.LOCK) {
      return Set.copyOf(publishers.keySet());
    }
  }

  @Override
  final void validateConfiguration() {
    if (publishers.isEmpty()) {
      throw new IllegalStateException("Join requires at least one subscribed publisher");
    }
  }

  @Override
  protected final void initialize() {
    final long cancellations = cancellationCount();
    if (observedCancellations != cancellations) {
      pending.clear();
      observedCancellations = cancellations;
    }
    onSignal();
  }

  @Override
  protected final void process(final JoinInput<K, V> input) {
    if (!Objects.equals(publishers.get(input.publisherName()), input.publisherId())) {
      throw new IllegalArgumentException("Unregistered publisher: " + input.publisherName());
    }
    final long now = scheduler.nanoTime();
    expire(now);
    receive(input, now);
    scheduleNextDeadline();
  }

  private void receive(final JoinInput<K, V> input, final long now) {
    final K requestId = input.requestId();
    if (finished.contains(requestId)) {
      return;
    }
    Pending<V> request = pending.get(requestId);
    if (request == null) {
      if (pending.size() == policy.maxPendingRequests()) {
        remember(requestId);
        onFailure.accept(
            new JoinFailure<>(requestId, JoinFailure.Reason.CAPACITY, publishers.keySet()));
        return;
      }
      request = new Pending<>(now);
      pending.put(requestId, request);
    }
    // First value wins: duplicates never count as an additional required branch.
    request.values.putIfAbsent(input.publisherName(), input.value());
    if (request.values.keySet().containsAll(publishers.keySet())) {
      pending.remove(requestId);
      remember(requestId);
      // Finalize before invoking user code: failed side effects must not be replayed on restart.
      final R result = combine.apply(requestId, Map.copyOf(request.values));
      publish(input, result);
    }
  }

  @Override
  protected final void onSignal() {
    expire(scheduler.nanoTime());
    scheduleNextDeadline();
  }

  private void expire(final long now) {
    final Iterator<Map.Entry<K, Pending<V>>> iterator = pending.entrySet().iterator();
    while (iterator.hasNext()) {
      final Map.Entry<K, Pending<V>> entry = iterator.next();
      final Pending<V> request = entry.getValue();
      if (now - request.startedAt < policy.timeout().toNanos()) {
        break;
      }
      iterator.remove();
      final K requestId = entry.getKey();
      remember(requestId);
      final Set<String> missing = new HashSet<>(publishers.keySet());
      missing.removeAll(request.values.keySet());
      onFailure.accept(new JoinFailure<>(requestId, JoinFailure.Reason.TIMEOUT, missing));
    }
  }

  private void remember(final K requestId) {
    finished.add(requestId);
    if (finished.size() > policy.rememberedRequests()) {
      final Iterator<K> oldest = finished.iterator();
      oldest.next();
      oldest.remove();
    }
  }

  private void scheduleNextDeadline() {
    alarm.cancel();
    if (!pending.isEmpty()) {
      final Pending<V> first = pending.values().iterator().next();
      final long remaining = policy.timeout().toNanos() - (scheduler.nanoTime() - first.startedAt);
      alarm = scheduler.schedule(this::signal, Math.max(0, remaining));
    }
  }

  @Override
  protected final void cleanup() {
    alarm.cancel();
    if (isCancellationRequested()) {
      pending.clear();
    }
    // Keep partial requests, original deadlines, and duplicate history for an in-memory restart.
  }

  private static final class Pending<V> {
    private final long startedAt;
    private final Map<String, V> values = new HashMap<>();

    private Pending(final long startedAt) {
      this.startedAt = startedAt;
    }
  }
}
