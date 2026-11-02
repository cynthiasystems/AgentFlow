package com.cynthiasystems.agentflow.tasks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A bounded, FIFO mailbox with at most one active processor.
 *
 * @param <X> incoming message type
 */
// Mailbox ownership and lifecycle transitions intentionally share one state machine and lock.
@SuppressWarnings("PMD.GodClass")
public abstract class Task<X> {
  public static final int DEFAULT_MAILBOX_CAPACITY = 1024;
  private static final int BATCH_SIZE = 64;

  private final Object lock = new Object();
  private final String id = UUID.randomUUID().toString();
  private final String name;
  final TaskGraph graph = new TaskGraph();
  private final Deque<X> inbox = new ArrayDeque<>();
  private final int capacity;
  private volatile TaskState state = TaskState.CREATED;
  private volatile Throwable failure;
  private volatile CountDownLatch stopped = new CountDownLatch(0);
  private ExecutorService executor;
  private boolean ownsExecutor;
  private boolean scheduled;
  private boolean signaled;
  private boolean initialized;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Construction only validates mailbox configuration; no privileged resources")
  protected Task() {
    this(DEFAULT_MAILBOX_CAPACITY);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Construction only validates mailbox configuration; no privileged resources")
  protected Task(final int capacity) {
    this(UUID.randomUUID().toString(), capacity);
  }

  protected Task(final String name) {
    this(name, DEFAULT_MAILBOX_CAPACITY);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Construction only validates configuration; no privileged resources")
  protected Task(final String name, final int capacity) {
    this.name = Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Task name must not be blank");
    }
    if (capacity <= 0) {
      throw new IllegalArgumentException("Mailbox capacity must be positive");
    }
    this.capacity = capacity;
  }

  public final String name() {
    return name;
  }

  /** Validates and freezes all connected nodes before any worker starts. */
  public static void freezeGraph(final Collection<? extends Task<?>> roots) {
    TaskGraph.freeze(roots);
  }

  final void connectTo(final Task<?> target, final Runnable connection) {
    TaskGraph.connect(this, target, connection);
  }

  void validateConfiguration() {}

  public final String id() {
    return id;
  }

  public final TaskState state() {
    return state;
  }

  /** Returns the most recent processing or lifecycle failure, or null. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The original exception is intentionally exposed for failure diagnostics")
  public final Throwable failure() {
    return failure;
  }

  /** Starts this task with its own daemon executor. Prefer Agent for shared execution. */
  public final void start() {
    startInternal(null);
  }

  /** Starts on a shared asynchronous executor, which the caller owns. */
  public final void start(final ExecutorService sharedExecutor) {
    startInternal(Objects.requireNonNull(sharedExecutor, "executor"));
  }

  // Executor ownership requires identity; a new run explicitly clears the old failure.
  @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.NullAssignment"})
  private void startInternal(final ExecutorService sharedExecutor) {
    TaskGraph.freezeIfNeeded(this);
    synchronized (lock) {
      if (state == TaskState.STARTED) {
        if (sharedExecutor != null && executor != sharedExecutor) {
          throw new IllegalStateException("Task already belongs to another executor");
        }
        return;
      }
      if (scheduled) {
        throw new IllegalStateException("Wait for the previous run to stop before restarting");
      }
      ownsExecutor = sharedExecutor == null;
      executor = ownsExecutor ? newExecutor() : sharedExecutor;
      initialized = false;
      failure = null;
      stopped = new CountDownLatch(1);
      state = TaskState.STARTED;
      scheduled = true;
    }
    submitWorker();
  }

  private ExecutorService newExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          final Thread thread = new Thread(runnable, "agentflow-" + id);
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Enqueues a message, including before start or after stop. A full mailbox rejects immediately;
   * producers never wait for processing or invoke downstream processing inline.
   */
  public final void accept(final X message) {
    Objects.requireNonNull(message, "message");
    final boolean submit;
    synchronized (lock) {
      if (inbox.size() == capacity) {
        throw new RejectedExecutionException("Mailbox is full for task " + id);
      }
      inbox.addLast(message);
      submit = state == TaskState.STARTED && !scheduled;
      if (submit) {
        scheduled = true;
      }
    }
    if (submit) {
      submitWorker();
    }
  }

  public final int inboxSize() {
    synchronized (lock) {
      return inbox.size();
    }
  }

  /**
   * Schedules a coalesced control notification without consuming mailbox capacity. The hook runs
   * serially with message processing, and notifications received while stopped survive restart.
   */
  protected final void signal() {
    final boolean submit;
    synchronized (lock) {
      signaled = true;
      submit = state == TaskState.STARTED && !scheduled;
      if (submit) {
        scheduled = true;
      }
    }
    if (submit) {
      submitWorker();
    }
  }

  /** Requests stop without blocking. The current message finishes; queued messages are retained. */
  public final void stop() {
    boolean submit = false;
    synchronized (lock) {
      if (state == TaskState.CREATED) {
        state = TaskState.STOPPED;
      } else if (state == TaskState.STARTED) {
        state = TaskState.STOPPING;
        if (!scheduled) {
          scheduled = true;
          submit = true;
        }
      }
    }
    if (submit) {
      submitWorker();
    }
  }

  /** Waits for processing and lifecycle hooks to finish. Do not call from this task's hooks. */
  public final boolean awaitStopped(final Duration timeout) throws InterruptedException {
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("Timeout must not be negative");
    }
    return stopped.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  private void submitWorker() {
    try {
      executor.execute(this::runBatch);
    } catch (final RejectedExecutionException e) {
      finish(state == TaskState.STOPPING ? null : e);
    }
  }

  // Null is an internal control-event marker, never an accepted user message.
  @SuppressWarnings("PMD.NullAssignment")
  private void runBatch() {
    try {
      if (!initialized) {
        beforeStart();
        initialize();
        initialized = true;
      }
      for (int count = 0; count < BATCH_SIZE; count++) {
        final X message;
        synchronized (lock) {
          if (state != TaskState.STARTED || (inbox.isEmpty() && !signaled)) {
            break;
          }
          // Null is reserved for a control notification; accept() rejects null messages.
          message = signaled ? null : inbox.removeFirst();
          signaled = false;
        }
        if (message == null) {
          onSignal();
        } else {
          process(message);
        }
      }
    } catch (final Exception | Error e) {
      finish(e);
      return;
    }

    final boolean stopping;
    synchronized (lock) {
      stopping = state != TaskState.STARTED;
      if (!stopping && inbox.isEmpty() && !signaled) {
        // Enqueue and this transition share a lock, so an arrival cannot lose its wake-up.
        scheduled = false;
        return;
      }
    }
    if (stopping) {
      finish(null);
    } else {
      // Yield to other tasks, even if this mailbox receives messages continuously.
      submitWorker();
    }
  }

  private void finish(final Throwable cause) {
    state = TaskState.STOPPING;
    Throwable problem = cause;
    try {
      cleanup();
    } catch (final Exception | Error e) {
      problem = combine(problem, e);
    }
    try {
      afterStop();
    } catch (final Exception | Error e) {
      problem = combine(problem, e);
    }
    synchronized (lock) {
      if (ownsExecutor) {
        executor.shutdown();
      }
      failure = problem;
      state = problem == null ? TaskState.STOPPED : TaskState.FAILED;
      scheduled = false;
      stopped.countDown();
    }
  }

  // Throwable forbids self-suppression based on identity, regardless of equals().
  @SuppressWarnings("PMD.CompareObjectsWithEquals")
  private static Throwable combine(final Throwable first, final Throwable next) {
    if (first == null) {
      return next;
    }
    if (first != next) {
      first.addSuppressed(next);
    }
    return first;
  }

  /** Called once per start, before initialization, on the worker. */
  protected void beforeStart() {}

  /** Called once per start, before any messages are processed. */
  protected void initialize() {}

  /** Called on stop or failure, including when initialization fails. */
  protected void cleanup() {}

  /** Called after cleanup, even if cleanup fails. */
  protected void afterStop() {}

  /** Handles a coalesced control notification on the worker. */
  protected void onSignal() {}

  /** Processes one message. An exception fails this run; the failed message is not retried. */
  protected abstract void process(X message) throws Exception;
}
