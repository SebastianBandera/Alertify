package app.alertify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.execution.ResolvedAlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.binary.BinaryPayloadService;
import app.alertify.ai.AiToolSupport;
import app.alertify.codex.BrowserExtensionPackageService.ExtensionPackage;
import app.alertify.configuration.service.ApplicationConfigurationService;
import app.alertify.procedures.execution.ResolvedProcedureParameter;
import app.alertify.services.secret.EncryptedSecretValue;

class ArrayBackedRecordValueSemanticsTest {

    @Test
    void comparesDownloadsAndUploadsWithoutExposingTheirContents() throws Exception {
        byte[] content = "private-csv-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AiToolSupport support = new AiToolSupport(1024);
        var file = support.csv("data.csv", content);
        var first = support.csvUpload(file);
        var same = support.csvUpload(file);
        var different = support.csvUpload(support.csv("data.csv", DIFFERENT_BINARY_CONTENT));
        assertValueSemantics(first, same, different);
        assertThat(first).isNotEqualTo(support.csvUpload(support.csv("other.csv", content)));
        content[0] = 0;
        byte[] returned = first.getBytes();
        returned[0] = 0;
        assertThat(first.getBytes()).isEqualTo(same.getBytes());
        assertThat(first.toString()).contains("contentLength=19").doesNotContain("private-csv-content", Arrays.toString(first.getBytes()));

        ExtensionPackage firstPackage = new ExtensionPackage("extension.zip", first.getBytes());
        assertValueSemantics(firstPackage, new ExtensionPackage("extension.zip", same.getBytes()), new ExtensionPackage("extension.zip", DIFFERENT_BINARY_CONTENT));
        assertThat(firstPackage).isNotEqualTo(new ExtensionPackage("other.zip", same.getBytes()));
        assertThat(firstPackage.toString()).contains("contentsLength=19").doesNotContain("private-csv-content", Arrays.toString(first.getBytes()));
        assertValueSemantics(new ExtensionPackage(null, null), new ExtensionPackage(null, null), firstPackage);
        assertThat(new ExtensionPackage(null, null).toString()).contains("contentsLength=null");
    }

    private static final byte[] BINARY_CONTENT = { 91, 42, -7 };
    private static final byte[] DIFFERENT_BINARY_CONTENT = { 91, 42, -6 };

    @Test
    void comparesArrayComponentsByContent() {
        assertValueSemantics(
            preparedBinary(BINARY_CONTENT), preparedBinary(BINARY_CONTENT), preparedBinary(DIFFERENT_BINARY_CONTENT)
        );
        assertValueSemantics(
            binaryDownload(BINARY_CONTENT), binaryDownload(BINARY_CONTENT), binaryDownload(DIFFERENT_BINARY_CONTENT)
        );
        assertValueSemantics(
            resolvedAlert(BINARY_CONTENT), resolvedAlert(BINARY_CONTENT), resolvedAlert(DIFFERENT_BINARY_CONTENT)
        );
        assertValueSemantics(
            resolvedProcedure(BINARY_CONTENT), resolvedProcedure(BINARY_CONTENT), resolvedProcedure(DIFFERENT_BINARY_CONTENT)
        );
        assertValueSemantics(
            encryptedValue(BINARY_CONTENT), encryptedValue(BINARY_CONTENT), encryptedValue(DIFFERENT_BINARY_CONTENT)
        );
    }

    @Test
    void handlesNullOptionalArrays() {
        ResolvedAlertParameter firstAlert = resolvedAlert(null);
        ResolvedAlertParameter secondAlert = resolvedAlert(null);
        ResolvedProcedureParameter firstProcedure = resolvedProcedure(null);
        ResolvedProcedureParameter secondProcedure = resolvedProcedure(null);

        assertThat(firstAlert)
            .isEqualTo(secondAlert)
            .hasSameHashCodeAs(secondAlert);
        assertThat(firstAlert.toString()).contains("binaryZipLength=null");
        assertThat(firstProcedure)
            .isEqualTo(secondProcedure)
            .hasSameHashCodeAs(secondProcedure);
        assertThat(firstProcedure.toString()).contains("binaryZipLength=null");
    }

    @Test
    void doesNotExposeArrayOrSecretContentsInTextRepresentations() {
        String binaryText = Arrays.toString(BINARY_CONTENT);

        assertThat(preparedBinary(BINARY_CONTENT).toString())
            .contains("sha256Length=3", "zipLength=3")
            .doesNotContain(binaryText);
        assertThat(binaryDownload(BINARY_CONTENT).toString())
            .contains("contentLength=3")
            .doesNotContain(binaryText);
        assertThat(resolvedAlert(BINARY_CONTENT).toString())
            .contains("valuePresent=true", "binaryZipLength=3")
            .doesNotContain("plain-secret", binaryText);
        assertThat(resolvedProcedure(BINARY_CONTENT).toString())
            .contains("valuePresent=true", "binaryZipLength=3")
            .doesNotContain("plain-secret", binaryText);
        assertThat(encryptedValue(BINARY_CONTENT)).hasToString("EncryptedSecretValue");
    }

    private static void assertValueSemantics(Object first, Object same, Object different) {
        assertThat(first)
            .isEqualTo(same)
            .isNotEqualTo(different)
            .hasSameHashCodeAs(same);
    }

    private static BinaryPayloadService.PreparedBinary preparedBinary(byte[] content) {
        return new BinaryPayloadService.PreparedBinary(
            "binary.dat", "application/octet-stream", 3, 3, copy(content), copy(content)
        );
    }

    private static ApplicationConfigurationService.BinaryDownload binaryDownload(byte[] content) {
        return new ApplicationConfigurationService.BinaryDownload(
            "binary.dat", "application/octet-stream", copy(content)
        );
    }

    private static ResolvedAlertParameter resolvedAlert(byte[] content) {
        return new ResolvedAlertParameter(
            "payload", "byte[]", "plain-secret", copy(content), false, AlertParameterSource.SECRET,
            null, 7L, null, false
        );
    }

    private static ResolvedProcedureParameter resolvedProcedure(byte[] content) {
        return new ResolvedProcedureParameter(
            "payload", "byte[]", "plain-secret", copy(content), false, AlertParameterSource.SECRET,
            null, 7L, null, false
        );
    }

    private static EncryptedSecretValue encryptedValue(byte[] content) {
        return new EncryptedSecretValue(copy(content), copy(content), copy(content), copy(content), (short) 1);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
