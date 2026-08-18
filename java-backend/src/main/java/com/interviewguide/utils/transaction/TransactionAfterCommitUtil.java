package com.interviewguide.utils.transaction;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs side effects after a transaction commits, or immediately when no transaction is active. */
public final class TransactionAfterCommitUtil {
    /** Prevents construction of this stateless utility. */
    private TransactionAfterCommitUtil() {
    }

    /** Registers the callback at the transaction boundary used by Java task caches. */
    public static void run(Runnable action) {
        // Execute immediately for callers that are outside a Spring transaction.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        // Register only after the surrounding transaction has committed successfully.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Publish the cache side effect only after durable state is committed.
                action.run();
            }
        });
    }
}
