package com.cynthiasystems.agentflow.tasks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Transforms each input and enqueues the result in its subscribers' mailboxes.
 *
 * @param <X> incoming message type
 * @param <Y> outgoing message type
 */
public class RelayTask<X, Y> extends PublishingTask<X, Y> {
  private final Function<? super X, ? extends Y> expression;

  public RelayTask(final Function<? super X, ? extends Y> expression) {
    this(expression, DEFAULT_MAILBOX_CAPACITY);
  }

  public RelayTask(final Function<? super X, ? extends Y> expression, final int capacity) {
    super(capacity);
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  public RelayTask(final String name, final Function<? super X, ? extends Y> expression) {
    this(name, expression, DEFAULT_MAILBOX_CAPACITY);
  }

  public RelayTask(
      final String name, final Function<? super X, ? extends Y> expression, final int capacity) {
    super(name, capacity);
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  public static <X, Y> RelayTask<X, Y> of(final Function<? super X, ? extends Y> expression) {
    return new RelayTask<>(expression);
  }

  public static <X, Y> RelayTask<X, Y> of(
      final String name, final Function<? super X, ? extends Y> expression) {
    return new RelayTask<>(name, expression);
  }

  @Override
  protected void process(final X message) {
    publish(message, expression.apply(message));
  }
}
