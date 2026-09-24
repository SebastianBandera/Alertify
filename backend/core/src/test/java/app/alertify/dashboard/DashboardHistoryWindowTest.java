package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DashboardHistoryWindowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Mock private SystemConfigurationRepository repository;

    @Test
    void readsTheConfiguredDays() {
        configure("{\"days\":7}");

        assertThat(new DashboardHistoryWindow(repository).days()).isEqualTo(7);
    }

    @Test
    void fallsBackToTheDefaultWhenTheEntryIsMissing() {
        when(repository.findByNameIgnoreCase(DashboardHistoryWindow.CONFIGURATION_NAME)).thenReturn(Optional.empty());

        assertThat(new DashboardHistoryWindow(repository).days()).isEqualTo(DashboardHistoryWindow.DEFAULT_DAYS);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"days\":0}", "{\"days\":-1}", "{\"days\":2.5}", "{\"days\":\"7\"}", "{\"days\":400}", "{}", "7" })
    void fallsBackToTheDefaultWhenTheValueIsMalformed(String json) {
        configure(json);

        assertThat(new DashboardHistoryWindow(repository).days()).isEqualTo(DashboardHistoryWindow.DEFAULT_DAYS);
    }

    private void configure(String json) {
        when(repository.findByNameIgnoreCase(DashboardHistoryWindow.CONFIGURATION_NAME))
                .thenReturn(Optional.of(new SystemConfiguration(DashboardHistoryWindow.CONFIGURATION_NAME, JSON.readTree(json), false)));
    }
}
