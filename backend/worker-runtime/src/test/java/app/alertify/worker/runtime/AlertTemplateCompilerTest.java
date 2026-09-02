package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.AlertParameter;

class AlertTemplateCompilerTest {

    private static final String CLASS_NAME = "dynamic.SampleAlert";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void compilesCachesAndCreatesAFreshTemplateInstanceForEveryInvocation() throws Exception {
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties());
        String source = """
                package dynamic;

                import java.util.Map;
                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;

                public final class SampleAlert implements AlertEvaluator {
                    private final String value;
                    private int invocationCount;

                    public SampleAlert(String value) {
                        this.value = value;
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        invocationCount++;
                        context.setState(value + ":" + invocationCount);
                        return AlertResult.success(Map.of("invocationCount", invocationCount));
                    }
                }
                """;
        String checksum = sha256(source);

        compiler.synchronize(CLASS_NAME, checksum, source);
        compiler.synchronize(CLASS_NAME, checksum, source);

        assertThat(compiler.isAvailable(CLASS_NAME, checksum)).isTrue();
        CompiledAlertTemplate template = compiler.get(CLASS_NAME, checksum);
        AlertParameter parameter = AlertParameter.newBuilder()
                .setName("value")
                .setJavaType(String.class.getName())
                .setValue("sample")
                .build();
        var first = template.newInstance(java.util.List.of(parameter));
        var second = template.newInstance(java.util.List.of(parameter));
        AlertExecutionContext firstContext = new AlertExecutionContext();
        AlertExecutionContext secondContext = new AlertExecutionContext();

        first.evaluate(firstContext);
        second.evaluate(secondContext);

        assertThat(first).isNotSameAs(second);
        assertThat(firstContext.getState()).isEqualTo("sample:1");
        assertThat(secondContext.getState()).isEqualTo("sample:1");
    }

    @Test
    void rejectsSourceThatDoesNotMatchTheDeclaredChecksum() throws Exception {
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties());
        String source = "package dynamic; public final class SampleAlert {}";

        assertThatThrownBy(() -> compiler.synchronize(CLASS_NAME, sha256(source + "changed"), source))
                .isInstanceOf(TemplateCompilationException.class)
                .hasMessageContaining("does not match its checksum");
    }

    @Test
    void returnsCompilerDiagnosticsForInvalidSource() throws Exception {
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties());
        String source = "package dynamic; public final class SampleAlert { invalid }";

        assertThatThrownBy(() -> compiler.synchronize(CLASS_NAME, sha256(source), source))
                .isInstanceOf(TemplateCompilationException.class)
                .hasMessageContaining("compilation failed")
                .hasMessageContaining("line 1");
    }

    private WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties(
                "test-worker", 0, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1,
                temporaryDirectory.resolve("compiled"), null,
                new WorkerRuntimeProperties.Tls(false, null, null, null)
        );
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }
}
