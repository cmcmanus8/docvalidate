package com.docvalidate.service.application;

import com.docvalidate.service.persistence.ValidationRequestRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Without this, EXPIRED only ever happened to a caller who came back too late, and an
 * abandoned request sat in PENDING_UPLOAD for good. The status is meant to describe the
 * lifecycle, so something has to advance it whether or not anyone asks.
 */
@Component
@ConditionalOnProperty(name = "docvalidate.sweeper.enabled", havingValue = "true", matchIfMissing = true)
class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final ValidationRequestRepository requests;
    private final Clock clock;

    ExpirySweeper(ValidationRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${docvalidate.sweeper.interval:PT1M}")
    void expireAbandonedRequests() {
        int expired = requests.expireAbandoned(clock.instant());
        if (expired > 0) {
            log.info("Expired {} request(s) whose upload window had closed", expired);
        }
    }
}
