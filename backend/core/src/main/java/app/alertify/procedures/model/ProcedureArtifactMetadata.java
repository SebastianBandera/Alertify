package app.alertify.procedures.model;

import java.util.Arrays;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Historical metadata only; worker artifact identifiers and paths are never persisted. */
@Entity
@Table(name = "procedure_artifacts", schema = "core")
public class ProcedureArtifactMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "procedure_execution_id", nullable = false, updatable = false)
    private ProcedureExecution execution;

    @Column(name = "output_key", nullable = false, updatable = false, columnDefinition = "text")
    private String outputKey;

    @Column(name = "file_name", nullable = false, updatable = false, columnDefinition = "text")
    private String fileName;

    @Column(name = "media_type", nullable = false, updatable = false, columnDefinition = "text")
    private String mediaType;

    @Column(nullable = false, updatable = false)
    private long size;

    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] sha256;

    protected ProcedureArtifactMetadata() {
    }

    public ProcedureArtifactMetadata(ProcedureExecution execution, String outputKey, String fileName, String mediaType, long size, byte[] sha256) {
        this.execution = Objects.requireNonNull(execution);
        this.outputKey = Objects.requireNonNull(outputKey);
        this.fileName = Objects.requireNonNull(fileName);
        this.mediaType = Objects.requireNonNull(mediaType);
        if (size < 0)
            throw new IllegalArgumentException("size must not be negative");

        this.size = size;
        this.sha256 = Objects.requireNonNull(sha256).clone();
        if (this.sha256.length != 32)
            throw new IllegalArgumentException("sha256 must contain 32 bytes");
    }

    public Long getId() { return id; }
    public ProcedureExecution getExecution() { return execution; }
    public String getOutputKey() { return outputKey; }
    public String getFileName() { return fileName; }
    public String getMediaType() { return mediaType; }
    public long getSize() { return size; }
    public byte[] getSha256() { return Arrays.copyOf(sha256, sha256.length); }
}
