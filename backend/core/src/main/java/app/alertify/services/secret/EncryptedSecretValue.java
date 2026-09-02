package app.alertify.services.secret;

/**
 * Immutable result of secret encryption. Byte arrays are defensively copied
 * on construction and access to prevent callers from mutating key material.
 */
public record EncryptedSecretValue(
    byte[] encryptedValue,
    byte[] encryptionIv,
    byte[] valueHash,
    byte[] hashSalt,
    short encryptionVersion
) {

    public EncryptedSecretValue {
        encryptedValue = encryptedValue.clone();
        encryptionIv = encryptionIv.clone();
        valueHash = valueHash.clone();
        hashSalt = hashSalt.clone();
    }

    @Override
    public byte[] encryptedValue() {
        return encryptedValue.clone();
    }

    @Override
    public byte[] encryptionIv() {
        return encryptionIv.clone();
    }

    @Override
    public byte[] valueHash() {
        return valueHash.clone();
    }

    @Override
    public byte[] hashSalt() {
        return hashSalt.clone();
    }
}
