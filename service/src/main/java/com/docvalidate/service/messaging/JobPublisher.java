package com.docvalidate.service.messaging;

public interface JobPublisher {

    void publish(ValidationJob job);
}
