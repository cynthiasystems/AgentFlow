package com.cynthiasystems.agentflow.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.Singular;
import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

@Accessors(fluent = true, chain = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AdaptiveRelayTask<X, Y> extends AdaptiveTimingTask {
  @Singular List<X> inbox = new ArrayList<>();

  @Singular List<AdaptiveRelayTask<Y, ?>> relays = new ArrayList<>();

  Function<X, Y> expression;

  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public AdaptiveRelayTask(@NonNull final Function<X, Y> expression) {
    this.expression = expression;
  }

  @Override
  @Synchronized
  protected boolean shouldProcess() {
    return !inbox.isEmpty();
  }

  @Override
  @SneakyThrows
  @Synchronized
  protected void process() {
    for (final X x : inbox) {
      final Y result = expression.apply(x);
      for (final AdaptiveRelayTask<Y, ?> relay : relays) {
        relay.accept(result);
      }
    }
    inbox.clear();
  }

  @Synchronized
  public void accept(@NonNull final X message) {
    inbox.add(message);
  }
}
