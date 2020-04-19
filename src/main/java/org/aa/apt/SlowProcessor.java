package org.aa.apt;

public final class SlowProcessor implements Processor {

  private final int slowness;
  private Integer context;

  public SlowProcessor(int slowness) {
    this.slowness = slowness;
  }

  @Override
  public void handleContext(Integer context) {
    System.out.println(String.format("Context: %d", context));
    sleep();
    this.context = context;
  }

  @Override
  public Integer processValue(Integer value) {
    if (this.context == null) {
      throw new NoContextException();
    } else {
      System.out.println(String.format("Value: %d", value));
      return context * value;
    }
  }

  void sleep() {

    try {
      Thread.sleep(slowness);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
