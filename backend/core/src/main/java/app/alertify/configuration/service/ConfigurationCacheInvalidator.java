package app.alertify.configuration.service;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts cached configuration responses only after a successful transaction
 * commit, preventing Redis from observing data that may still roll back.
 */
@Component
class ConfigurationCacheInvalidator {

    private final CacheManager cacheManager;

    ConfigurationCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    void evictAfterCommit(Long id) {
        runAfterCommit(() -> evict(id));
    }

    void clearAfterCommit() {
        runAfterCommit(this::clear);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );
            return;
        }
        action.run();
    }

    private void evict(Long id) {
        Cache byId = cacheManager.getCache(ConfigurationCacheNames.BY_ID);
        if (byId != null && id != null)
            byId.evict(id);
    }

    private void clear() {
        Cache byId = cacheManager.getCache(ConfigurationCacheNames.BY_ID);
        if (byId != null)
            byId.clear();
    }
}
