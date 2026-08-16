package app.alertify.jpa.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class AlertifyJackson3JsonFormatMapperTest {

    private final AlertifyJackson3JsonFormatMapper mapper =
        new AlertifyJackson3JsonFormatMapper();

    @Test
    void mapsJsonNodesInBothDirections() {
        JsonNode value = mapper.fromString(
            "{\"enabled\":true,\"attempts\":3}", JsonNode.class
        );

        String json = mapper.toString(value, JsonNode.class);
        JsonNode roundTrip = mapper.fromString(json, JsonNode.class);

        assertThat(roundTrip).isEqualTo(value);
    }
}
