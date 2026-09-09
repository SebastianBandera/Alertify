package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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
    void loadsAnyNonEmptyKeyPartAsUtf8Bytes() {
        String keyPart = "A key part with symbols: ñ-🔐-!@#$%^&*()";
        SystemConfiguration configuration = new SystemConfiguration(
            "KEY_PART", null, StringNode.valueOf(keyPart)
        );
        when(systemConfigurationRepository.findByNameIgnoreCase("KEY_PART")).thenReturn(Optional.of(configuration));

        byte[] value = new DatabaseKeyPartSource(systemConfigurationRepository).read();

        assertThat(value).containsExactly(keyPart.getBytes(StandardCharsets.UTF_8));
    }
}
