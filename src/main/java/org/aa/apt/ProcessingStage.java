package org.aa.apt;

import akka.stream.Attributes;
import akka.stream.FanInShape2;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;

public class ProcessingStage extends GraphStage<FanInShape2<Integer, Integer, Integer>> {

  private final Inlet<Integer> inContext = new Inlet<Integer>("inContext");
  private final Inlet<Integer> inValue = new Inlet<Integer>("inValue");
  private final Outlet<Integer> outResult = new Outlet<Integer>("outResult");
  private final FanInShape2<Integer, Integer, Integer> shape =
      new FanInShape2<Integer, Integer, Integer>(inContext, inValue, outResult);

  private final Processor processor;

  public ProcessingStage(Processor processor) {
    this.processor = processor;
  }

  public GraphStageLogic createLogic(Attributes inheritedAttributes) {
    return new GraphStageLogic(shape) {
      {
        setHandler(
            inContext,
            new AbstractInHandler() {
              public void onPush() {
                processor.handleContext(grab(inContext));
                pull(inContext);
              }
            });

        setHandler(
            inValue,
            new AbstractInHandler() {
              public void onPush() {
                Integer input = grab(inValue);
                Integer result = processor.processValue(input);
                emit(outResult, result);
                pull(inValue);
              }
            });

        setHandler(
            outResult,
            new AbstractOutHandler() {
              @Override
              public void onPull() {}
            });
      }

      @Override
      public void preStart() {
        pull(inContext);
        pull(inValue);
      }
    };
  }

  public FanInShape2<Integer, Integer, Integer> shape() {
    return shape;
  }
}
