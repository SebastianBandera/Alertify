package app.alertify.worker.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import app.alertify.worker.grpc.AlertParameter;

final class AlertParameterConverter {

    private AlertParameterConverter() {
    }

    static Object convert(AlertParameter parameter, Class<?> targetType) {
        if (parameter.getNullValue()) {
            if (targetType.isPrimitive())
                return primitiveDefault(targetType);

            return null;
        }

        String value = parameter.getValue();
        if (targetType == String.class)
            return value;

        if (targetType == byte.class || targetType == Byte.class)
            return Byte.valueOf(value);

        if (targetType == short.class || targetType == Short.class)
            return Short.valueOf(value);

        if (targetType == int.class || targetType == Integer.class)
            return Integer.valueOf(value);

        if (targetType == long.class || targetType == Long.class)
            return Long.valueOf(value);

        if (targetType == float.class || targetType == Float.class)
            return Float.valueOf(value);

        if (targetType == double.class || targetType == Double.class)
            return Double.valueOf(value);

        if (targetType == boolean.class || targetType == Boolean.class)
            return Boolean.valueOf(value);

        if (targetType == char.class || targetType == Character.class) {
            if (value.length() != 1)
                throw new IllegalArgumentException("Character parameter must contain exactly one character");

            return value.charAt(0);
        }
        if (targetType == BigInteger.class)
            return new BigInteger(value);

        if (targetType == BigDecimal.class)
            return new BigDecimal(value);

        if (targetType == URI.class)
            return URI.create(value);

        if (targetType == Duration.class)
            return Duration.parse(value);

        if (targetType == Instant.class)
            return Instant.parse(value);

        if (targetType.isEnum())
            return enumValue(targetType, value);

        throw new IllegalArgumentException("Unsupported alert parameter type " + targetType.getName());
    }

    static String serialize(Object value, Class<?> declaredType) {
        if (value == null)
            return null;

        if (declaredType.isEnum())
            return ((Enum<?>) value).name();

        if (declaredType == String.class || declaredType == Character.class || declaredType == char.class
                || declaredType == URI.class || declaredType == Duration.class || declaredType == Instant.class
                || declaredType == BigInteger.class || declaredType == BigDecimal.class
                || declaredType == Byte.class || declaredType == byte.class
                || declaredType == Short.class || declaredType == short.class
                || declaredType == Integer.class || declaredType == int.class
                || declaredType == Long.class || declaredType == long.class
                || declaredType == Float.class || declaredType == float.class
                || declaredType == Double.class || declaredType == double.class
                || declaredType == Boolean.class || declaredType == boolean.class) {
            return value.toString();
        }

        throw new IllegalArgumentException("Unsupported writable alert parameter type " + declaredType.getName());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object enumValue(Class<?> targetType, String value) {
        return Enum.valueOf((Class<? extends Enum>) targetType, value);
    }

    private static Object primitiveDefault(Class<?> targetType) {
        if (targetType == boolean.class)
            return false;

        if (targetType == char.class)
            return '\0';

        if (targetType == byte.class)
            return (byte) 0;

        if (targetType == short.class)
            return (short) 0;

        if (targetType == int.class)
            return 0;

        if (targetType == long.class)
            return 0L;

        if (targetType == float.class)
            return 0F;

        if (targetType == double.class)
            return 0D;

        throw new IllegalArgumentException("Unsupported primitive type " + targetType.getName());
    }
}
