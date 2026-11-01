package app.alertify.configuration.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import app.alertify.binary.BinaryPayloadService;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ConfigurationBinaryValueRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;

/** Builds the real persistence service while making accidental cached verification fail the integration test. */
public final class PatchConfigurationTestServices {
    private PatchConfigurationTestServices() {
    }

    public static ApplicationConfigurationService create(ApplicationConfigurationRepository configurations, TagRepository tags) {
        var lookup = mock(ApplicationConfigurationLookupService.class);
        when(lookup.getById(any())).thenThrow(new AssertionError("PATCH verification must bypass the configuration cache"));
        return new ApplicationConfigurationService(configurations, tags, new ConfigurationValueValidator(), lookup,
                mock(ConfigurationCacheInvalidator.class), mock(ConfigurationCsvCodec.class), mock(ConfigurationExpressionService.class),
                mock(ApplicationEventLogger.class), mock(ConfigurationBinaryValueRepository.class), mock(BinaryPayloadService.class));
    }
}
