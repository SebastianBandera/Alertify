package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import tools.jackson.databind.node.StringNode;

class ConfigurationMapperTest {

    @Test
    void preservesConfigurationValues() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "mail.host", null, ConfigurationValueType.STRING,
            StringNode.valueOf("smtp.example.test"), Set.of()
        );

        var response = ConfigurationMapper.toResponse(configuration);

        assertThat(response.value().stringValue()).isEqualTo("smtp.example.test");
    }
}
