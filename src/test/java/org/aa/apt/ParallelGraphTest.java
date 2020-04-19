package org.aa.apt;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.dispatch.ForkJoinExecutorConfigurator;
import akka.stream.*;
import akka.stream.javadsl.GraphDSL;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import scala.concurrent.ExecutionContextExecutor;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

public class ParallelGraphTest {

  ActorSystem actorSystem;

  TestPublisher.Probe<Integer> contextProbe;
  TestPublisher.Probe<Integer> valueProbe;
  TestSubscriber.Probe<Integer> resultProbe;

  @Before
  public void setUp() {
    actorSystem = ActorSystem.create();
  }

  private ClosedShape build(GraphDSL.Builder<NotUsed> builder, int parallelism, int slowness) {

    FanInShape2<Integer, Integer, Integer> parallelGraph =
        ParallelGraph.buildGraph(parallelism, () -> new SlowProcessor(slowness), builder);

    contextProbe = new TestPublisher.Probe<>(1, actorSystem);
    builder.from(builder.add(Source.fromPublisher(contextProbe))).toInlet(parallelGraph.in0());

    valueProbe = new TestPublisher.Probe<>(1, actorSystem);
    builder.from(builder.add(Source.fromPublisher(valueProbe))).toInlet(parallelGraph.in1());

    resultProbe = new TestSubscriber.Probe<>(actorSystem);
    builder.from(parallelGraph.out()).to(builder.add(Sink.fromSubscriber(resultProbe)));

    return ClosedShape.getInstance();
  }

  void prepare(int parallelism, int slowness) {
    ActorMaterializerSettings settings = ActorMaterializerSettings.create(actorSystem);
    // .withInputBuffer(1, 1).withDebugLogging(true);
    Materializer materializer = ActorMaterializer.create(settings, actorSystem);

    materializer.materialize(GraphDSL.create(b -> build(b, parallelism, slowness)));
  }

  /** No parallelism involve: the test is reliable */
  @Test
  public void testFastNoParallelism() {

    prepare(1, 0);

    // resultProbe.ensureSubscription();
    contextProbe.sendNext(10);
    valueProbe.sendNext(2);

    Assert.assertEquals((Integer) 20, (Integer) resultProbe.requestNext());
  }

  /** Some processors receive the input before they get the initial context */
  @Test
  public void testFastWithParallelism() {

    prepare(10, 100);

    resultProbe.ensureSubscription();
    contextProbe.sendNext(10);
    valueProbe.sendNext(2);

    Throwable exception = resultProbe.expectError();
    Assert.assertEquals(exception.getClass(), NoContextException.class);
  }

  /** Sleep long enough to make sure all context are received. */
  @Test
  public void testFastWithParallelismWaitForContext() {

    prepare(10, 100);

    resultProbe.ensureSubscription();
    contextProbe.sendNext(10);
    sleep(200);
    valueProbe.sendNext(2);

    Assert.assertEquals((Integer) 20, (Integer) resultProbe.requestNext());
  }

  /** It takes too long for the context to be processed, so the test fails */
  @Test(expected = AssertionError.class)
  public void testTooSlow() {

    prepare(100, 2000);

    resultProbe.ensureSubscription();
    contextProbe.sendNext(10);
    sleep(100);
    valueProbe.sendNext(2);

    Assert.assertEquals((Integer) 20, (Integer) resultProbe.requestNext());
  }

  /** Correct wait to wait for completion */
  @Test
  public void testReliable() {

    prepare(10, 2000);

    // resultProbe.ensureSubscription();
    contextProbe.sendNext(10);
    awaitQuiescence(10000);
    valueProbe.sendNext(2);

    Assert.assertEquals((Integer) 20, (Integer) resultProbe.requestNext());
  }

  public void awaitQuiescence(int millis) {

    ExecutionContextExecutor dispatcher = actorSystem.dispatcher();
    Object executorServiceDelegate =
        getAttribute(dispatcher, "executorServiceDelegate", Object.class);
    System.out.println(executorServiceDelegate.getClass().getName());
    ForkJoinExecutorConfigurator.AkkaForkJoinPool executor =
        getAttribute(
            executorServiceDelegate,
            "executor",
            ForkJoinExecutorConfigurator.AkkaForkJoinPool.class);
    Assert.assertTrue(executor.awaitQuiescence(millis, TimeUnit.MILLISECONDS));
  }

  public static <T> T getAttribute(Object object, String attributeName, Class<T> type) {
    try {
      Field field = object.getClass().getDeclaredField(attributeName);
      field.setAccessible(true);
      return type.cast(field.get(object));
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
