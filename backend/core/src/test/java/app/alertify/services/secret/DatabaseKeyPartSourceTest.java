package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class DatabaseKeyPartSourceTest {

    @Mock private SystemConfigurationRepository systemConfigurationRepository;

    @Test
    void loadsAnyNonEmptyKeyPart() {
        String keyPart = "A key part with symbols: ñ-🔐-!@#$%^&*()";
        SystemConfiguration configuration = new SystemConfiguration(
            "KEY_PART", StringNode.valueOf(keyPart), true
        );
        when(systemConfigurationRepository.findByNameIgnoreCase("KEY_PART")).thenReturn(Optional.of(configuration));

        String value = new DatabaseKeyPartSource(systemConfigurationRepository).read();

        assertThat(value).isEqualTo(keyPart);
    }
}
