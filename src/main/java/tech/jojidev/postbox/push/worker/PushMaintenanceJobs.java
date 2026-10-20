package tech.jojidev.postbox.push.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.jojidev.postbox.push.persistence.PushDeliveryStore;

@Component
public class PushMaintenanceJobs {
    private final PushDeliveryStore store;
    private final int maxAttempts;
    private final int retentionDays;

    public PushMaintenanceJobs(PushDeliveryStore store,
                               @Value("${postbox.push.max-attempts:5}") int maxAttempts,
                               @Value("${postbox.push.retention-days:30}") int retentionDays) {
        this.store = store;
        this.maxAttempts = maxAttempts;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${postbox.push.recovery-interval-ms:30000}")
    public void recover() { store.recover(maxAttempts); }

    @Scheduled(cron = "0 0 3 * * *")
    public void purge() { store.purge(retentionDays); }
}
