package app.alertify.procedures.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import app.alertify.procedures.ProcedureExecutionContext;

class ConsoleParameterProcedureTemplateTest {

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void printsTheConfiguredValueToStandardOutput() {
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            var result = new ConsoleParameterProcedureTemplate("sample value")
                    .execute(new ProcedureExecutionContext(Instant.now(), Map.of()));

            assertEquals("sample value", result.get("printedValue").asString());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("sample value"));
        } finally {
            System.setOut(previousOutput);
        }
    }
}
