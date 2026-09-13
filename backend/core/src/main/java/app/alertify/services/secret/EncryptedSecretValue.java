package app.alertify.services.secret;

import java.util.Arrays;

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

    @Override
    public boolean equals(Object object) {
        if (this == object)
            return true;

        if (!(object instanceof EncryptedSecretValue other))
            return false;

        return encryptionVersion == other.encryptionVersion
                && Arrays.equals(encryptedValue, other.encryptedValue)
                && Arrays.equals(encryptionIv, other.encryptionIv)
                && Arrays.equals(valueHash, other.valueHash)
                && Arrays.equals(hashSalt, other.hashSalt);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(encryptedValue);
        result = 31 * result + Arrays.hashCode(encryptionIv);
        result = 31 * result + Arrays.hashCode(valueHash);
        result = 31 * result + Arrays.hashCode(hashSalt);
        return 31 * result + Short.hashCode(encryptionVersion);
    }

    @Override
    public String toString() {
        return "EncryptedSecretValue";
    }
}
