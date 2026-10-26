package com.deductionaudit.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final ExpiryService expiryService;

    public ExpiryJob(ExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(cron = "${app.expiry.cron:0 0 1 * * *}")
    public void run() {
        int expired = expiryService.expireDue();
        log.info("expiry sweep expired {} deductions", expired);
    }
}
