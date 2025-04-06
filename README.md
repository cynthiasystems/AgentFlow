# AgentFlow

A robust Java framework for orchestrating persistent, asynchronous AI agent tasks in a pub-sub directed graph architecture designed for neverending, open-ended workflows.

![Agent Flow](assets/AgentFlow.png)

## Overview

AgentFlow enables the creation of autonomous, long-running agent systems where independent tasks communicate through message passing. Built with resilience in mind, it employs an adaptive timing mechanism that allows agents to efficiently manage their processing cycles based on workload.

## Core Architecture

AgentFlow is built around a few key architectural concepts:

### Task-Based Concurrency

Each operation in AgentFlow is encapsulated as a `Task` that runs in its own daemon thread. Tasks are self-contained units of work that can:
- Process incoming messages
- Adapt their processing frequency
- Communicate with other tasks
- Maintain their own state

### Pub-Sub Message Passing

Tasks communicate through a publish-subscribe pattern:
- Tasks can subscribe to (relay from) other tasks
- Publishers don't need to know about their subscribers
- Messages flow through a directed graph of tasks

### Adaptive Timing

Instead of fixed polling intervals, AgentFlow uses an exponential weighted moving average (EWMA) algorithm to adjust each task's processing frequency based on recent activity patterns:
- Tasks that process more messages get more CPU time
- Idle tasks automatically back off to conserve resources
- The system naturally balances between responsiveness and efficiency

## Key Components

### Task

The foundational abstract class that:
- Manages its own thread lifecycle
- Implements the core processing loop
- Provides hooks for subclasses
- Handles graceful startup and shutdown

```java
public abstract class Task implements Runnable {
    // Thread lifecycle management
    public void start() { ... }
    public void stop() { ... }

    // Abstract methods for subclasses
    protected abstract boolean shouldProcess();
    protected abstract void process();
    protected abstract long calculateSleepTime();
}
```

### AdaptiveTimingTask

Extends `Task` with intelligence about when to process:

```java
public abstract class AdaptiveTimingTask extends Task {
    double alpha = 0.1;  // Learning rate
    double estimatedSleepTime = 100;  // Starting sleep time (ms)

    @Override
    protected long calculateSleepTime() {
        // Use EWMA algorithm to adapt sleep time based on
        // time between successful processing cycles
        if (lastWaitingTime() == 0) {
            return (long) estimatedSleepTime;
        }
        estimatedSleepTime = alpha * lastWaitingTime() + (1 - alpha) * estimatedSleepTime;
        return (long) estimatedSleepTime;
    }
}
```

The adaptive timing mechanism ensures:
- Busy tasks run more frequently (shorter sleep times)
- Idle tasks conserve resources (longer sleep times)
- System responds dynamically to changing load patterns

### AdaptiveRelayTask

The core component for building agent pipelines:

```java
public class AdaptiveRelayTask<X, Y> extends AdaptiveTimingTask {
    List<X> inbox = new ArrayList<>();
    List<AdaptiveRelayTask<Y, ?>> relays = new ArrayList<>();
    Function<X, Y> expression;

    @Override
    protected boolean shouldProcess() {
        return !inbox.isEmpty();
    }

    @Override
    protected void process() {
        for (X x : inbox) {
            Y result = expression.apply(x);
            for (AdaptiveRelayTask<Y, ?> relay : relays) {
                relay.accept(result);
            }
        }
        inbox.clear();
    }

    public void accept(X message) {
        inbox.add(message);
    }

    public void relay(AdaptiveRelayTask<Y, ?> relay) {
        relays.add(relay);
    }
}
```

This class provides:
- Generic type parameters for input and output message types
- A functional interface for transforming input to output
- Inbox management for message queuing
- Automatic message routing to downstream tasks

### Agent

A container that manages collections of tasks:

```java
public class Agent {
    List<Task> tasks;

    public void start() {
        for (Task task : tasks) {
            task.start();
        }
    }

    public void stop() {
        for (Task task : tasks) {
            task.stop();
        }
    }
}
```

## How It Works

1. **Create Task Instances**: Define your pipeline by creating tasks that process different types of messages
2. **Connect Tasks**: Use `task.relay(nextTask)` to establish connections between tasks
3. **Create an Agent**: Group your tasks into an Agent for lifecycle management
4. **Start the Agent**: The agent starts all tasks in their own threads
5. **Send Initial Messages**: Kick off processing by sending messages to the first task
6. **Autonomous Processing**: Tasks automatically process messages, adapt their timing, and relay results

The system runs indefinitely until explicitly stopped, making it ideal for persistent agent applications.

## Example: AI News Agent

```java
// Create tasks
SearchSourcesTask searchTask = SearchSourcesTask.of();
ContentCollectionTask contentTask = ContentCollectionTask.of();
InterestingTweetTask tweetTask = InterestingTweetTask.of();
TwitterPostTask postTask = TwitterPostTask.of();

// Connect the relay chain
searchTask.relay(contentTask);
contentTask.relay(tweetTask);
tweetTask.relay(postTask);

// Create and start the agent
Agent agent = Agent.builder()
    .task(searchTask)
    .task(contentTask)
    .task(tweetTask)
    .task(postTask)
    .build();

// Kick off with initial configuration
AgentConfig config = AgentConfig.of(urlPatterns);
searchTask.accept(config);

// Start the agent
agent.start();
```

This creates a complete pipeline that:
1. Discovers content from configured sources
2. Extracts and processes the content
3. Evaluates interestingness and generates tweets
4. Posts the most interesting content to Twitter

## Benefits

- **Loose Coupling**: Tasks only know about the messages they receive and produce
- **Resilience**: Each task operates independently; failure in one doesn't crash others
- **Adaptability**: Processing frequency automatically adjusts to workload
- **Composition**: Complex workflows can be built by connecting simple tasks
- **Type Safety**: Generic typing ensures messages are compatible
- **Extensibility**: New task types can be easily added to the system

## Getting Started

1. Add AgentFlow to your Maven project:
```xml
<dependency>
    <groupId>com.cynthiasystems</groupId>
    <artifactId>AgentFlow</artifactId>
    <version>1.0.0</version>
</dependency>
```

2. Create your custom tasks by extending `AdaptiveRelayTask`:
```java
public class MyTask extends AdaptiveRelayTask<InputType, OutputType> {
    public MyTask() {
        super(input -> {
            // Process input and return output
            return processFunction(input);
        });
    }

    private static OutputType processFunction(InputType input) {
        // Custom processing logic
    }
}
```

3. Connect tasks and start an agent (see example above)

## Best Practices

- **Small, Focused Tasks**: Each task should do one thing well
- **Immutable Messages**: Use immutable data structures for messages
- **Error Handling**: Ensure tasks handle exceptions and continue operating
- **Resource Management**: Close resources in the cleanup method
- **Monitoring**: Log key events and metrics from your tasks

## Advanced Usage

- **Fan-out**: Connect one task to multiple downstream tasks
- **Fan-in**: Have multiple tasks send to a single downstream task
- **Filtering**: Create tasks that only relay certain messages based on criteria
- **Transformation**: Use tasks to convert between different message formats
- **Aggregation**: Collect messages over time before processing

## Contributing

Contributions welcome! Please feel free to submit a Pull Request.

## License

[MIT License](LICENSE)
