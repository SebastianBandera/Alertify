package app.alertify.secret.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SecretResponseTest {

    @Test
    void publicResponseHasNoValueOrCryptographicMaterial() {
        assertThat(Arrays.stream(SecretResponse.class.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("value", "newValue", "encryptedValue", "encryptionIv", "valueHash", "hashSalt", "encryptionVersion");
    }
}
