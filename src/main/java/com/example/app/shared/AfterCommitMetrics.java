package com.example.app.shared;

import io.micrometer.core.instrument.Counter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Increments a Micrometer counter only after the surrounding transaction commits.
 *
 * <p>Micrometer counters are not transactional: an increment inside a {@code @Transactional}
 * method survives a later rollback. Registering the increment as an after-commit
 * synchronization ties the metric to the committed state change it describes.
 *
 * <p>When no transaction synchronization is active (unit tests, non-transactional paths)
 * the counter is incremented immediately - there is no transaction to roll back.
 */
public final class AfterCommitMetrics {

    private AfterCommitMetrics() {
    }

    public static void incrementAfterCommit(Counter counter) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }
}