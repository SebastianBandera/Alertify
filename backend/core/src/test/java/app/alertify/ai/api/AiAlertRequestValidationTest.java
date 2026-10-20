package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.model.SmartExecutionPolicy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AiAlertRequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheStrictSchemaPlaceholderWhenSmartExecutionIsDisabled() {
        var create = new AiAlertCreateRequest(1L, "Alert", "", "-", true, false, List.of(), Set.of(), false,
                false, 0, SmartExecutionPolicy.NORMAL);
        var update = new AiAlertUpdateRequest(0L, "Alert", "", "-", true, false, List.of(), Set.of(), false,
                false, 0, SmartExecutionPolicy.NORMAL);

        assertThat(VALIDATOR.validate(create)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
    }
}
