package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.service.ApplicationConfigurationLookupService;
import app.alertify.jpa.entity.ConfigurationValueType;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class DatabaseKeyPartSourceTest {

    @Mock private ApplicationConfigurationLookupService configurationLookup;

    @Test
    void loadsAnyNonEmptyKeyPartAsUtf8Bytes() {
        String keyPart = "A key part with symbols: ñ-🔐-!@#$%^&*()";
        ConfigurationResponse configuration = new ConfigurationResponse(
            1L, 0L, "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf(keyPart), Set.of(), true, false, null, null, null
        );
        when(configurationLookup.getByName("KEY_PART")).thenReturn(configuration);

        byte[] value = new DatabaseKeyPartSource(configurationLookup).read();

        assertThat(value).containsExactly(keyPart.getBytes(StandardCharsets.UTF_8));
    }
}
