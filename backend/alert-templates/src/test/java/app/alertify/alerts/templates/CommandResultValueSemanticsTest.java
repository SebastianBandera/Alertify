package app.alertify.alerts.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.templates.KubernetesWorkloadAlertTemplate.CommandResult;

class CommandResultValueSemanticsTest {

    @Test
    void comparesAllComponentsAndKeepsKubeconfigOutOfText() {
        byte[] stdout = "private-kubeconfig".getBytes(StandardCharsets.UTF_8);
        CommandResult first = new CommandResult(0, stdout, null, false, false);
        CommandResult same = new CommandResult(0, stdout.clone(), null, false, false);
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(first).isNotEqualTo(new CommandResult(1, stdout.clone(), null, false, false));
        assertThat(first).isNotEqualTo(new CommandResult(0, new byte[0], null, false, false));
        assertThat(first).isNotEqualTo(new CommandResult(0, stdout.clone(), "error", false, false));
        assertThat(first).isNotEqualTo(new CommandResult(0, stdout.clone(), null, true, false));
        assertThat(first).isNotEqualTo(new CommandResult(0, stdout.clone(), null, false, true));
        assertThat(first.toString()).contains("stdoutLength=18").doesNotContain("private-kubeconfig", Arrays.toString(stdout));
        CommandResult empty = new CommandResult(0, null, null, false, false);
        assertThat(empty).isEqualTo(new CommandResult(0, null, null, false, false))
                .hasSameHashCodeAs(new CommandResult(0, null, null, false, false));
        assertThat(empty.toString()).contains("stdoutLength=null");
    }
}
