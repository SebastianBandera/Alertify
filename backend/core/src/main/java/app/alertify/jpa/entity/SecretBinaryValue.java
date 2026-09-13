package app.alertify.jpa.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "secret_binary_values", schema = "secrets")
public class SecretBinaryValue {
    @Id
    @Column(name = "secret_id")
    private Long secretId;
    @Column(name = "encrypted_value", nullable = false, columnDefinition = "bytea") private byte[] encryptedValue;
    @Column(name = "encryption_iv", nullable = false, columnDefinition = "bytea") private byte[] encryptionIv;
    @Column(name = "value_hash", nullable = false, columnDefinition = "bytea") private byte[] valueHash;
    @Column(name = "hash_salt", nullable = false, columnDefinition = "bytea") private byte[] hashSalt;
    @Column(name = "encryption_version", nullable = false) private short encryptionVersion;

    protected SecretBinaryValue() { }

    public SecretBinaryValue(Long secretId, byte[] encryptedValue, byte[] encryptionIv, byte[] valueHash, byte[] hashSalt, short encryptionVersion) {
        this.secretId = Objects.requireNonNull(secretId);
        replace(encryptedValue, encryptionIv, valueHash, hashSalt, encryptionVersion);
    }

    public Long getSecretId() { return secretId; }
    public byte[] getEncryptedValue() { return encryptedValue.clone(); }
    public byte[] getEncryptionIv() { return encryptionIv.clone(); }
    public byte[] getValueHash() { return valueHash.clone(); }
    public byte[] getHashSalt() { return hashSalt.clone(); }
    public short getEncryptionVersion() { return encryptionVersion; }
    public void replace(byte[] value, byte[] iv, byte[] hash, byte[] salt, short version) {
        encryptedValue = Objects.requireNonNull(value).clone();
        encryptionIv = Objects.requireNonNull(iv).clone();
        valueHash = Objects.requireNonNull(hash).clone();
        hashSalt = Objects.requireNonNull(salt).clone();
        encryptionVersion = version;
    }
}
