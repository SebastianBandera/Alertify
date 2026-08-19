package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import tools.jackson.databind.node.StringNode;

class ConfigurationMapperTest {

    @Test
    void hidesKeyPartValueFromEveryMappedResponse() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf("must-never-reach-an-api-response"), Set.of()
        );

        var response = ConfigurationMapper.toResponse(configuration);

        assertThat(response.value()).isNull();
        assertThat(response.valueHidden()).isTrue();
    }

    @Test
    void preservesRegularConfigurationValues() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "mail.host", null, ConfigurationValueType.STRING,
            StringNode.valueOf("smtp.example.test"), Set.of()
        );

        var response = ConfigurationMapper.toResponse(configuration);

        assertThat(response.value().stringValue()).isEqualTo("smtp.example.test");
        assertThat(response.valueHidden()).isFalse();
    }
}
