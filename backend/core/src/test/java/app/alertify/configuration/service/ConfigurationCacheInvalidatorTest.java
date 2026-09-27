package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ConfigurationCacheInvalidatorTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void evictsOnlyAfterTheDatabaseTransactionCommits() {
        var cacheManager = new ConcurrentMapCacheManager(ConfigurationCacheNames.BY_ID);
        var byId = cacheManager.getCache(ConfigurationCacheNames.BY_ID);
        byId.put(7L, "old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        new ConfigurationCacheInvalidator(cacheManager)
            .evictAfterCommit(7L);

        assertThat(byId.get(7L)).isNotNull();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(TransactionSynchronization::afterCommit);
        assertThat(byId.get(7L)).isNull();
    }

    @Test
    void clearsConfigurationCacheAfterATagChangeCommits() {
        var cacheManager = new ConcurrentMapCacheManager(ConfigurationCacheNames.BY_ID);
        var byId = cacheManager.getCache(ConfigurationCacheNames.BY_ID);
        byId.put(7L, "old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        new ConfigurationCacheInvalidator(cacheManager).clearAfterCommit();

        assertThat(byId.get(7L)).isNotNull();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(TransactionSynchronization::afterCommit);
        assertThat(byId.get(7L)).isNull();
    }
}
