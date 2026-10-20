package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.services.secret.SecretAccessContext;
import app.alertify.services.secret.SecretAccessService;
import tools.jackson.databind.json.JsonMapper;

class PlaywrightValuePreparationServiceTest {
    @Test
    void extractsOnlyTheSelectedCredentialAndRejectsMissingFieldsAndWrongTypes() {
        var repository = mock(ApplicationSecretRepository.class);
        var access = mock(SecretAccessService.class);
        var secret = mock(ApplicationSecret.class);
        var context = SecretAccessContext.wizard("test");
        when(repository.findByNameIgnoreCase("Login")).thenReturn(Optional.of(secret));
        when(secret.getValueType()).thenReturn(SecretValueType.USERNAME_PASSWORD);
        when(access.getValueByName("Login", context)).thenReturn("{\"username\":\"test-user\",\"password\":\"private-password\"}");
        var service = new PlaywrightValuePreparationService(repository, mock(ProcedureRepository.class), access, null, JsonMapper.builder().build());
        var username = service.resolve(new PlaywrightPageEngine.ValueReference(AlertExecutionValueSource.SECRET, "Login", "/username"), context);
        assertThat(username.value()).isEqualTo("test-user");
        assertThat(username.value()).doesNotContain("private-password");
        when(access.getValueByName("Login", context)).thenReturn("{\"username\":null,\"password\":\"private-password\"}");
        assertThatThrownBy(() -> service.resolve(new PlaywrightPageEngine.ValueReference(AlertExecutionValueSource.SECRET, "Login", "/username"), context)).hasMessageContaining("not configured");
        when(secret.getValueType()).thenReturn(SecretValueType.STRING);
        assertThatThrownBy(() -> service.resolve(new PlaywrightPageEngine.ValueReference(AlertExecutionValueSource.SECRET, "Login", "/password"), context)).hasMessageContaining("USERNAME_PASSWORD");
    }
}
