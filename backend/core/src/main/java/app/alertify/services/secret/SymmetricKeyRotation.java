package app.alertify.services.secret;

/** Parsed and derived old/new key material for one startup evaluation. */
final class SymmetricKeyRotation implements AutoCloseable {

    private final boolean transition;
    private final ObfuscatedKeyMaterial oldKey;
    private final ObfuscatedKeyMaterial newKey;

    SymmetricKeyRotation(boolean transition, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey) {
        this.transition = transition;
        this.oldKey = oldKey;
        this.newKey = newKey;
    }

    boolean transition() {
        return transition;
    }

    ObfuscatedKeyMaterial oldKey() {
        return oldKey;
    }

    ObfuscatedKeyMaterial newKey() {
        return newKey;
    }

    @Override
    public void close() {
        oldKey.destroy();
        newKey.destroy();
    }
}
