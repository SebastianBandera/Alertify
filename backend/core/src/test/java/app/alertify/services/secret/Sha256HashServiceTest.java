package app.alertify.services.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class Sha256HashServiceTest {

    private final Sha256HashService service = new Sha256HashService();

    @Test
    void hashesStringUsingSha256() {
        String hash = HexFormat.of().formatHex(service.hash("abc"));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
    }

    @Test
    void hashesEmptyStringUsingSha256() {
        String hash = HexFormat.of().formatHex(service.hash(""));

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void rejectsNullString() {
        assertThrows(IllegalArgumentException.class, () -> service.hash((String) null));
    }
}
