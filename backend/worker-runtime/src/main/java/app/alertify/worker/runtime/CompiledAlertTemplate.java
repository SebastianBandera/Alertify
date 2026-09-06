package app.alertify.worker.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.procedures.Procedure;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.WritableConfigurationValue;
import app.alertify.worker.grpc.WritableSecretValue;
import io.grpc.Deadline;

/**
 * One compiled alert template class together with the checksum of the source it
 * was built from. Instances are created by reflection: the request parameters
 * are matched positionally against a constructor, a {@link Procedure} parameter
 * becomes a callable handle instead of a converted value, and after the run the
 * writable parameter fields are read back to detect the values the template
 * changed.
 */
record CompiledAlertTemplate(
    String checksum,
    Class<? extends AlertEvaluator> templateClass
) {

    AlertEvaluator newInstance(List<AlertParameter> parameters) {
        return newInstance(parameters, null, null);
    }

    AlertEvaluator newInstance(List<AlertParameter> parameters, ProcedureHandleFactory handles, Deadline deadline) {
        Constructor<?> constructor = matchingConstructor(parameters);
        Object[] values = new Object[parameters.size()];
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        for (int index = 0; index < values.length; index++) {
            AlertParameter parameter = parameters.get(index);
            if (!parameter.getJavaType().equals(parameterTypes[index].getName())) {
                throw new IllegalArgumentException(
                    "Parameter '" + parameter.getName() + "' expected "
                        + parameterTypes[index].getName() + " but received " + parameter.getJavaType()
                );
            }
            values[index] = parameterTypes[index] == Procedure.class && !parameter.getNullValue()
                    ? requireHandles(handles).create(parameter, deadline)
                    : AlertParameterConverter.convert(parameter, parameterTypes[index]);
        }
        try {
            constructor.setAccessible(true);
            return (AlertEvaluator) constructor.newInstance(values);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Could not create alert template " + templateClass.getName(), exception
            );
        }
    }

    private static ProcedureHandleFactory requireHandles(ProcedureHandleFactory handles) {
        if (handles == null)
            throw new IllegalStateException("Procedure handles are unavailable");

        return handles;
    }

    WritableValues writableValues(AlertEvaluator evaluator, List<AlertParameter> parameters) {
        List<WritableConfigurationValue> configurationValues = new ArrayList<>();
        List<WritableSecretValue> secretValues = new ArrayList<>();
        for (AlertParameter parameter : parameters) {
            if (!parameter.getWritable())
                continue;

            boolean configurationTarget = parameter.getConfigurationId() > 0;
            boolean secretTarget = parameter.getSecretId() > 0;
            if (configurationTarget == secretTarget)
                throw new IllegalArgumentException("Writable parameter '" + parameter.getName() + "' must have exactly one target");

            try {
                Field field = templateClass.getDeclaredField(parameter.getName());
                field.setAccessible(true);
                Object finalValue = field.get(evaluator);
                Object initialValue = AlertParameterConverter.convert(parameter, field.getType());
                if (Objects.equals(initialValue, finalValue))
                    continue;

                String serialized = finalValue == null
                        ? null
                        : AlertParameterConverter.serialize(finalValue, field.getType());
                if (configurationTarget) {
                    WritableConfigurationValue.Builder value = WritableConfigurationValue.newBuilder()
                            .setConfigurationId(parameter.getConfigurationId())
                            .setParameterName(parameter.getName())
                            .setNullValue(finalValue == null);
                    if (serialized != null)
                        value.setValue(serialized);

                    configurationValues.add(value.build());
                } else {
                    WritableSecretValue.Builder value = WritableSecretValue.newBuilder()
                            .setSecretId(parameter.getSecretId())
                            .setParameterName(parameter.getName())
                            .setNullValue(finalValue == null);
                    if (serialized != null)
                        value.setValue(serialized);

                    secretValues.add(value.build());
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not collect writable parameter '" + parameter.getName() + "'", exception);
            }
        }
        return new WritableValues(List.copyOf(configurationValues), List.copyOf(secretValues));
    }

    private Constructor<?> matchingConstructor(List<AlertParameter> parameters) {
        for (Constructor<?> constructor : templateClass.getDeclaredConstructors()) {
            if (matches(constructor, parameters))
                return constructor;
        }
        throw new IllegalArgumentException(
            "Alert template " + templateClass.getName() + " has no constructor with "
                + parameters.size() + " parameters"
        );
    }

    private static boolean matches(Constructor<?> constructor, List<AlertParameter> parameters) {
        if (constructor.getParameterCount() != parameters.size())
            return false;

        Class<?>[] types = constructor.getParameterTypes();
        java.lang.reflect.Parameter[] constructorParameters = constructor.getParameters();
        for (int index = 0; index < types.length; index++) {
            AlertParameter parameter = parameters.get(index);
            if (!types[index].getName().equals(parameter.getJavaType()) || !constructorParameters[index].getName().equals(parameter.getName()))
                return false;
        }
        return true;
    }

    record WritableValues(
        List<WritableConfigurationValue> configurationValues,
        List<WritableSecretValue> secretValues
    ) {
    }
}
