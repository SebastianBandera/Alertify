package app.alertify.services.secret;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.springframework.stereotype.Component;

@Component
class PrivateClassKeyPartSource {

    private static final String DEFAULT_CLASS_NAME =
        "app.alertify.services.secret.key.PrivateKeyPart";
    private static final String DEFAULT_FIELD_NAME = "KEY_PART";

    private final String className;
    private final String fieldName;

    PrivateClassKeyPartSource() {
        this(DEFAULT_CLASS_NAME, DEFAULT_FIELD_NAME);
    }

    PrivateClassKeyPartSource(String className, String fieldName) {
        this.className = className;
        this.fieldName = fieldName;
    }

    String read() {
        try {
            Class<?> keyPartClass = Class.forName(className);
            Field field = keyPartClass.getDeclaredField(fieldName);
            int modifiers = field.getModifiers();

            if (field.getType() != String.class
                    || !Modifier.isPrivate(modifiers)
                    || !Modifier.isFinal(modifiers)
                    || !Modifier.isStatic(modifiers)) {
                return "";
            }

            field.setAccessible(true);
            String value = (String) field.get(null);
            return value == null ? "" : value;
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            return "";
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                "The private symmetric-key part could not be read",
                e
            );
        }
    }
}
