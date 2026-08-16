package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class DatabaseKeyPartSourceTest {

    @Mock private ApplicationConfigurationRepository repository;

    @Test
    void loadsAnyNonEmptyKeyPartAsUtf8Bytes() {
        String keyPart = "A key part with symbols: ñ-🔐-!@#$%^&*()";
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "KEY_PART", null, ConfigurationValueType.STRING, StringNode.valueOf(keyPart), Set.of()
        );
        when(repository.findByName("KEY_PART")).thenReturn(Optional.of(configuration));

        byte[] value = new DatabaseKeyPartSource(repository).read();

        assertThat(value).containsExactly(keyPart.getBytes(StandardCharsets.UTF_8));
    }
}
