package app.alertify.services.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PrivateClassKeyPartSourceTest {

    @Test
    void returnsEmptyPartWhenGeneratedClassDoesNotExist() {
        PrivateClassKeyPartSource source = new PrivateClassKeyPartSource(
            "app.alertify.services.secret.missing.DoesNotExist",
            "KEY_PART"
        );

        assertEquals("", source.read());
    }

    @Test
    void readsPrivateStaticFinalStringByReflection() {
        PrivateClassKeyPartSource source = new PrivateClassKeyPartSource(
            Fixture.class.getName(),
            "KEY_PART"
        );

        assertEquals("fixture-private-key-part", source.read());
    }

    @Test
    void returnsEmptyPartWhenExpectedFieldDoesNotExist() {
        PrivateClassKeyPartSource source = new PrivateClassKeyPartSource(
            Fixture.class.getName(),
            "MISSING"
        );

        assertEquals("", source.read());
    }

    private static final class Fixture {
        private static final String KEY_PART = "fixture-private-key-part";
    }
}
