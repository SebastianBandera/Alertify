package app.alertify.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;

import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.jpa.entity.ConfigurationValueType;
import tools.jackson.databind.node.StringNode;

class AlertifyRedisCacheConfigurationTest {

    @Test
    void serializesAndDeserializesConfigurationResponsesAsJson() {
        var serializer = new JacksonJsonRedisSerializer<>(ConfigurationResponse.class);
        var response = new ConfigurationResponse(
            1L, 2L, "mail.host", "SMTP host", ConfigurationValueType.STRING,
            StringNode.valueOf("smtp.example.test"), Set.of(),
            false, true, null, null, null
        );

        byte[] serialized = serializer.serialize(response);
        ConfigurationResponse restored = serializer.deserialize(serialized);

        assertThat(restored).isEqualTo(response);
        assertThat(new String(serialized, java.nio.charset.StandardCharsets.UTF_8))
            .contains("mail.host", "smtp.example.test");
    }
}
