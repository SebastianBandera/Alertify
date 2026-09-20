package app.alertify.hooks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.api.error.InvalidHookImportException;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;

class HookCsvCodecTest {
    private static final String HEADER = "publicId,name,description,enabled,mode,tokenSecret,maxConcurrentInvocations,rateLimitCount,rateLimitWindowSeconds,targets";
    private final HookCsvCodec codec = new HookCsvCodec(JsonMapper.builder().build());

    @Test
    void exportsReferencesByNameOnly() {
        ApplicationSecret secret = new ApplicationSecret("HOOK_TOKEN", null,
                "cipher-text".getBytes(StandardCharsets.UTF_8), new byte[12], new byte[32],
                new byte[16], (short) 1, Set.of());
        ReflectionTestUtils.setField(secret, "id", 3L);
        Hook hook = new Hook("nightly", "Runs at night", HookMode.SEQUENTIAL, secret, 1, 5, 60L);
        ReflectionTestUtils.setField(hook, "publicId", UUID.fromString("11111111-2222-3333-4444-555555555555"));
        Alert alert = new Alert(new AlertTemplateDefinition("template.A", "name", "description", "source.java", WorkerCapability.STANDARD),
                "Disk usage", null, "0 0 * * * *", true);
        ReflectionTestUtils.setField(alert, "id", 9L);
        hook.replaceTargets(List.of(HookTarget.alert(hook, alert, 0, List.of("SUCCESS", "WARN"), 1000L)));

        String csv = new String(codec.write(List.of(hook)), StandardCharsets.UTF_8);

        assertThat(csv).contains(HEADER)
                .contains("11111111-2222-3333-4444-555555555555,nightly,Runs at night,false,SEQUENTIAL,HOOK_TOKEN,1,5,60,")
                .contains("\"\"type\"\":\"\"ALERT\"\",\"\"name\"\":\"\"Disk usage\"\",\"\"continueOn\"\":[\"\"SUCCESS\"\",\"\"WARN\"\"],\"\"busyWaitTimeoutMillis\"\":1000")
                .doesNotContain("cipher-text").doesNotContain(",9,");
    }

    @Test
    void readsARowWithTargets() {
        String csv = "\uFEFF" + HEADER + "\r\n"
                + ",nightly,,true,parallel,HOOK_TOKEN,,3,30,\"[{\"\"type\"\":\"\"PROCEDURE\"\",\"\"name\"\":\"\"Backup\"\",\"\"continueOn\"\":[\"\"ERROR\"\"]}]\"\r\n";

        HookCsvCodec.ReadResult result = codec.read(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.errors()).isEmpty();
        HookCsvCodec.ImportRow row = result.rows().getFirst();
        assertThat(row.publicId()).isNull();
        assertThat(row.enabled()).isTrue();
        assertThat(row.mode()).isEqualTo(HookMode.PARALLEL);
        assertThat(row.tokenSecret()).isEqualTo("HOOK_TOKEN");
        assertThat(row.maxConcurrentInvocations()).isNull();
        assertThat(row.rateLimitCount()).isEqualTo(3);
        assertThat(row.rateLimitWindowSeconds()).isEqualTo(30L);
        assertThat(row.targets()).singleElement().satisfies(target -> {
            assertThat(target.type()).isEqualTo(HookTargetType.PROCEDURE);
            assertThat(target.name()).isEqualTo("Backup");
            assertThat(target.continueOn()).containsExactly(HookOutcome.ERROR);
            assertThat(target.busyWaitTimeoutMillis()).isEqualTo(HookTarget.DEFAULT_BUSY_WAIT_MILLIS);
        });
    }

    @Test
    void reportsInvalidRowsWithoutBlockingTheOthers() {
        String csv = HEADER + "\r\n"
                + ",bad-mode,,false,SOMETIMES,,,,,[]\r\n"
                + ",bad-json,,false,PARALLEL,,,,,[oops\r\n"
                + ",bad-outcome,,false,PARALLEL,,,,,\"[{\"\"type\"\":\"\"ALERT\"\",\"\"name\"\":\"\"A\"\",\"\"continueOn\"\":[\"\"MAYBE\"\"]}]\"\r\n"
                + ",half-limit,,false,PARALLEL,,,4,,[]\r\n"
                + ",ok,,false,PARALLEL,,,,,[]\r\n";

        HookCsvCodec.ReadResult result = codec.read(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.name()).isEqualTo("ok"));
        assertThat(result.errors()).extracting(error -> error.row() + ":" + error.name()).containsExactly("2:bad-mode", "3:bad-json", "4:bad-outcome", "5:half-limit");
        assertThat(result.errors().get(0).message()).contains("mode");
        assertThat(result.errors().get(1).message()).contains("JSON");
        assertThat(result.errors().get(2).message()).contains("continueOn");
        assertThat(result.errors().get(3).message()).contains("together");
    }

    @Test
    void rejectsAnUnexpectedHeader() {
        assertThatThrownBy(() -> codec.read("name\r\nvalue\r\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidHookImportException.class)
                .hasMessageContaining("header");
    }
}
