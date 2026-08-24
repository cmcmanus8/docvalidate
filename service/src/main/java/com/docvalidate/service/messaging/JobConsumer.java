package com.docvalidate.service.messaging;

public interface JobConsumer {

    void onJob(ValidationJob job);
}
