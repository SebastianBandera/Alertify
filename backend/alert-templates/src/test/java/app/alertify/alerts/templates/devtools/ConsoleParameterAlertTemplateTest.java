package app.alertify.alerts.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.execution.AlertExecutionStatus;

class ConsoleParameterAlertTemplateTest {

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void printsTheConfiguredValueToStandardOutput() {
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            var result = new ConsoleParameterAlertTemplate("sample value")
                    .evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals("sample value", result.statusMessage().get("printedValue"));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("sample value"));
        } finally {
            System.setOut(previousOutput);
        }
    }
}
