Reliable Akka Stream Parallel Tests
------


Akka Stream is a powerful tool for writing parallel processing pipelines without having to worry about the tedious thread syncrhonization details.

Ideally you want to test your pipelines. 
It's easy to write unit tests for non-parallel streams. 
But with parallelism it becomes harder to ensure repeatability because of timeliness issues.


# Test Scenario

In our test we use a stateful `Processor`. It receives: 
* `context` that updates every now and then. 
* `input` that are updated very often and are used with the context to calculate the output   



```$xslt
              +----------------+
+---Context-->|                |
              |    Processor   |------ Output ---->
+---Input---->|                |
              +----------------+
```

In our example `output = context * input`. 
But in a real life example, the calculation is more complicated and both updating the context and calculating the output would take some time (a few milliseconds).

In order to process a high load of input, we run many processors in parallel and we: 
* Broadcast contexts to each processor, so each processor gets the updated context.
* Distribute inputs to each processor, so each input is processed by only one processor.
* Results are then merged.

**The Processor needs to receive the context before processing any input**

# The problem with testing async akka streams

In a non-parallel/synchronous akka stream, you get some guarantees in the timeliness of event. 
For example if  context is sent followed by input, the processor is guaranteed to have received the context before it receives the input.

In a parallel/asynchronous akka stream, you don't get these guarantees. 
It means that when testing, after the initial context is sent to the many processors, you need to wait for all of them to have handle the context before sending the input.

If inputs are received before the context, the test will fail and become flaky.

**This the problem we're trying to solve here.**

# Using sleep statement

The first solution consist in using a sleep statement:
* Send the initial context
* Sleep to wait for the context to be propagated to all Processor
* Send the first input

While this seems to work in theory there are a few issues.

The longer the sleep, the more likely the test is to behave correctly and pass. 
But the test takes longer, and most of this time could actually be wasted because you don't know when all Processors had received the context.

Tests are likely to be run on various environment. 
For example a development computer and a Continuous Integration (CI) server.
CI environment are usually under high load and test runs are significantly slower there. 
This makes it hard to select the correct sleep time.

For all these reason this is not a suitable solution.

# Using acknowledgments

Another solution would be to let the processors send an ack when they received the context. 
Then you'd have to wait for every ack before sending the input.


```$xslt
              +----------------+
+---Context-->|                |------ Output ---->
              |    Processor   |
+---Input---->|                |------ Ack ------->
              +----------------+
```

While this work in practice, it is changing the graph and making it more cumbersome.
Also in production, the ack has to be handled (or sent to an ignore sink) which doesn't feel natural. 


# Waiting for the Executor to finish

Akka stream hides a lot of the implementation details of the framework.
In particular the way events are processed and dispatched to each graph stage.

This a good thing, because it means we don't have to deal with these details. 
We just implement different stages, put them together in a graph and let Akka do the rest.

But in this test scenario, it would be nice to have access at the inner details of Akka, find the underlying [Executor](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/Executor.html) and use it to figure out the exact time at which the test graph is done processing contexts.

As a matter of fact, this is possible. It's just a bit tricky to access the `Executor`, because it is a nested private member accessible from the [ActorSystem](https://doc.akka.io/api/akka/current/akka/actor/ActorSystem.html).

The exact path is the following: `actorSystem.dispatcher().executorServiceDelegate.executor`. The executor is an [AkkaForkJoinPool](https://doc.akka.io/japi/akka/current/akka/dispatch/ForkJoinExecutorConfigurator.AkkaForkJoinPool.html).
It implements [awaitQuiescence](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html#awaitQuiescence-long-java.util.concurrent.TimeUnit-) which blocks until the executor is done processing events, which is exactly what we want.

For more details look at [ParallelGraphTest](/src/test/java/org/aa/apt/ParallelGraphTest.java)
