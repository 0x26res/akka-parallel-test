package org.aa.apt;

public interface Processor {


    void handleContext(Integer context);

    Integer processValue(Integer value);
}
