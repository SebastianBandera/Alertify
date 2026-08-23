package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
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
        var cacheManager = new ConcurrentMapCacheManager(
            ConfigurationCacheNames.BY_ID, ConfigurationCacheNames.BY_NAME
        );
        var byId = cacheManager.getCache(ConfigurationCacheNames.BY_ID);
        var byName = cacheManager.getCache(ConfigurationCacheNames.BY_NAME);
        byId.put(7L, "old");
        byName.put("old-name", "old");
        byName.put("new-name", "old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        new ConfigurationCacheInvalidator(cacheManager)
            .evictAfterCommit(7L, Set.of("old-name", "new-name"));

        assertThat(byId.get(7L)).isNotNull();
        assertThat(byName.get("old-name")).isNotNull();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCommit());
        assertThat(byId.get(7L)).isNull();
        assertThat(byName.get("old-name")).isNull();
        assertThat(byName.get("new-name")).isNull();
    }

    @Test
    void clearsBothConfigurationCachesAfterATagChangeCommits() {
        var cacheManager = new ConcurrentMapCacheManager(
            ConfigurationCacheNames.BY_ID, ConfigurationCacheNames.BY_NAME
        );
        var byId = cacheManager.getCache(ConfigurationCacheNames.BY_ID);
        var byName = cacheManager.getCache(ConfigurationCacheNames.BY_NAME);
        byId.put(7L, "old");
        byName.put("configuration", "old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        new ConfigurationCacheInvalidator(cacheManager).clearAfterCommit();

        assertThat(byId.get(7L)).isNotNull();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCommit());
        assertThat(byId.get(7L)).isNull();
        assertThat(byName.get("configuration")).isNull();
    }
}
