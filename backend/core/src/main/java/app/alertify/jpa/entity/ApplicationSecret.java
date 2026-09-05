package app.alertify.jpa.entity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditJoinTable;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Persistent secret metadata plus encrypted value material. The original
 * plaintext is never stored, and Envers audits metadata and revision numbers
 * without keeping historical secret values.
 */
@Entity
@Audited
@AuditTable(value = "secrets_aud", schema = "audit")
@Table(
    name = "secrets",
    schema = "secrets",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_secrets_name",
        columnNames = "name"
    )
)
public class ApplicationSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @NotAudited
    @Column(name = "encrypted_value", nullable = false, columnDefinition = "bytea")
    private byte[] encryptedValue;

    @NotAudited
    @Column(name = "encryption_iv", nullable = false, columnDefinition = "bytea")
    private byte[] encryptionIv;

    @NotAudited
    @Column(name = "value_hash", nullable = false, columnDefinition = "bytea")
    private byte[] valueHash;

    @NotAudited
    @Column(name = "hash_salt", nullable = false, columnDefinition = "bytea")
    private byte[] hashSalt;

    @Column(name = "encryption_version", nullable = false)
    private short encryptionVersion;

    @Column(name = "value_revision", nullable = false)
    private long valueRevision;

    @Column(nullable = false)
    private boolean writable;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "secret_tag",
        schema = "secrets",
        joinColumns = @JoinColumn(name = "secret_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @AuditJoinTable(name = "secret_tag_aud", schema = "audit")
    private Set<Tag> tags = new LinkedHashSet<>();

    protected ApplicationSecret() {
    }

    public ApplicationSecret(String name, String description, byte[] encryptedValue, byte[] encryptionIv, byte[] valueHash, byte[] hashSalt, short encryptionVersion, Set<Tag> tags) {
        this(name, description, encryptedValue, encryptionIv, valueHash, hashSalt, encryptionVersion, tags, false);
    }

    public ApplicationSecret(String name, String description, byte[] encryptedValue, byte[] encryptionIv, byte[] valueHash, byte[] hashSalt, short encryptionVersion, Set<Tag> tags, boolean writable) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        setEncryptedValue(encryptedValue, encryptionIv, valueHash, hashSalt, encryptionVersion);
        valueRevision = 1;
        this.writable = writable;
        replaceTags(tags);
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public byte[] getEncryptedValue() {
        return encryptedValue.clone();
    }

    public byte[] getEncryptionIv() {
        return encryptionIv.clone();
    }

    public byte[] getValueHash() {
        return valueHash.clone();
    }

    public byte[] getHashSalt() {
        return hashSalt.clone();
    }

    public short getEncryptionVersion() {
        return encryptionVersion;
    }

    public long getValueRevision() {
        return valueRevision;
    }

    public boolean isWritable() {
        return writable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<Tag> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void replaceEncryptedValue(byte[] encryptedValue, byte[] encryptionIv, byte[] valueHash, byte[] hashSalt, short encryptionVersion) {
        setEncryptedValue(encryptedValue, encryptionIv, valueHash, hashSalt, encryptionVersion);
        valueRevision++;
    }

    public void changeWritable(boolean writable) {
        this.writable = writable;
    }

    public void replaceTags(Set<Tag> tags) {
        this.tags.clear();
        if (tags != null)
            this.tags.addAll(tags);
    }

    private void setEncryptedValue(byte[] encryptedValue, byte[] encryptionIv, byte[] valueHash, byte[] hashSalt, short encryptionVersion) {
        this.encryptedValue = Objects.requireNonNull(encryptedValue, "encryptedValue must not be null").clone();
        this.encryptionIv = Objects.requireNonNull(encryptionIv, "encryptionIv must not be null").clone();
        this.valueHash = Objects.requireNonNull(valueHash, "valueHash must not be null").clone();
        this.hashSalt = Objects.requireNonNull(hashSalt, "hashSalt must not be null").clone();
        this.encryptionVersion = encryptionVersion;
    }
}
