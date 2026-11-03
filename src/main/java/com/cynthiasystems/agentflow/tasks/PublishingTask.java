package com.cynthiasystems.agentflow.tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A task with subscriptions fixed before execution and automatic publisher identity for joins.
 *
 * @param <X> incoming message type
 * @param <Y> outgoing value type
 */
public abstract class PublishingTask<X, Y> extends Task<X> {
  private final List<BiConsumer<X, Y>> subscriptions = new ArrayList<>();
  private final Set<Task<?>> targets = new HashSet<>();
  private final List<Task<?>> advisoryTargets = new ArrayList<>();

  protected PublishingTask(final int capacity) {
    super(capacity);
  }

  protected PublishingTask(final String name, final int capacity) {
    super(name, capacity);
  }

  public final void relay(final Task<? super Y> target) {
    addSubscription(target, () -> subscriptions.add((input, value) -> target.accept(value)));
  }

  /** Connects to a join, deriving the correlation ID from the original input before publication. */
  public final <K> void relay(
      final JoinTask<K, ? super Y, ?> target, final Function<? super X, ? extends K> requestId) {
    relay(target, requestId, input -> true);
  }

  /** Connects a conditional publication, useful for stopping feedback at a logical-step limit. */
  public final <K> void relay(
      final JoinTask<K, ? super Y, ?> target,
      final Function<? super X, ? extends K> requestId,
      final Predicate<? super X> publishWhen) {
    Objects.requireNonNull(publishWhen, "publishWhen");
    Objects.requireNonNull(requestId, "requestId");
    addSubscription(
        target,
        () -> {
          target.registerPublisher(this);
          subscriptions.add(
              (input, value) -> {
                if (publishWhen.test(input)) {
                  target.acceptPublication(requestId.apply(input), this, value);
                }
              });
        });
  }

  /** Adds an advisory-only subscriber; it does not become a required join publisher. */
  public final void relayAdvisories(final Task<?> target) {
    connectTo(
        target,
        () -> {
          if (advisoryTargets.contains(target)) {
            throw new IllegalArgumentException(
                "Duplicate advisory subscription to " + target.name());
          }
          advisoryTargets.add(target);
        });
  }

  /** Broadcasts an optional message or task command through the advisory subscriptions. */
  protected final void publishAdvisory(final Object message) {
    Objects.requireNonNull(message, "message");
    for (final Task<?> target : advisoryTargets) {
      target.advise(message);
    }
  }

  private void addSubscription(final Task<?> target, final Runnable registration) {
    connectTo(
        target,
        () -> {
          if (targets.contains(target)) {
            throw new IllegalArgumentException("Duplicate subscription to " + target.name());
          }
          registration.run();
          targets.add(target);
        });
  }

  /** Publishes outside mailbox locks; the graph is immutable by the time processing starts. */
  protected final void publish(final X input, final Y value) {
    for (final BiConsumer<X, Y> subscription : subscriptions) {
      subscription.accept(input, value);
    }
  }
}
