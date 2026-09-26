package app.alertify.secret.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class DatabaseSecretTestResponseTest {

    @Test
    void publicResponseHasNoFreeFormFailureMessage() {
        assertThat(Arrays.stream(DatabaseSecretTestResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("connected", "failureReason")
                .doesNotContain("failureMessage");
    }
}
