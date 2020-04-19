package org.aa.apt;

import akka.stream.FanInShape2;
import akka.stream.UniformFanInShape;
import akka.stream.UniformFanOutShape;
import akka.stream.javadsl.Balance;
import akka.stream.javadsl.Broadcast;
import akka.stream.javadsl.GraphDSL;
import akka.stream.javadsl.Merge;

import java.util.function.Supplier;

public class ParallelGraph {

  public static FanInShape2<Integer, Integer, Integer> buildGraph(
      int parallelism, Supplier<Processor> processorSupplier, GraphDSL.Builder<?> builder) {

    UniformFanOutShape<Integer, Integer> broadcastContext =
        builder.add(Broadcast.create(parallelism));
    UniformFanOutShape<Integer, Integer> balanceInput = builder.add(Balance.create(parallelism));

    UniformFanInShape<Integer, Integer> results = builder.add(Merge.create(parallelism));

    for (int i = 0; i < parallelism; ++i) {

      FanInShape2<Integer, Integer, Integer> processorShape =
          builder.add(
              parallelism == 1
                  ? new ProcessingStage(processorSupplier.get())
                  : new ProcessingStage(processorSupplier.get()).async());

      builder.from(broadcastContext).toInlet(processorShape.in0());
      builder.from(balanceInput).toInlet(processorShape.in1());
      builder.from(processorShape.out()).toFanIn(results);
    }

    return new FanInShape2<>(broadcastContext.in(), balanceInput.in(), results.out());
  }
}
