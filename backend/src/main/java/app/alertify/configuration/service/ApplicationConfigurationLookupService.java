package app.alertify.configuration.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;

@Service
public class ApplicationConfigurationLookupService {

    private final ApplicationConfigurationRepository configurationRepository;

    public ApplicationConfigurationLookupService(
            ApplicationConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
    }

    @Cacheable(cacheNames = ConfigurationCacheNames.BY_ID, key = "#id")
    @Transactional(readOnly = true)
    public ConfigurationResponse getById(Long id) {
        return configurationRepository.findById(id)
            .map(ConfigurationMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Configuration " + id + " was not found"
            ));
    }

    @Cacheable(cacheNames = ConfigurationCacheNames.BY_NAME, key = "#name")
    @Transactional(readOnly = true)
    public ConfigurationResponse getByName(String name) {
        return configurationRepository.findByName(name)
            .map(ConfigurationMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Configuration '" + name + "' was not found"
            ));
    }
}
