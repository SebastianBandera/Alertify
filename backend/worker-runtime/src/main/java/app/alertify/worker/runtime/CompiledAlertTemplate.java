package app.alertify.worker.runtime;

import java.lang.reflect.Constructor;
import java.util.List;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.worker.grpc.AlertParameter;

record CompiledAlertTemplate(
    String checksum,
    Class<? extends AlertEvaluator> templateClass
) {

    AlertEvaluator newInstance(List<AlertParameter> parameters) {
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
            values[index] = AlertParameterConverter.convert(parameter, parameterTypes[index]);
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

    private static boolean matches(
        Constructor<?> constructor,
        List<AlertParameter> parameters
    ) {
        if (constructor.getParameterCount() != parameters.size())
            return false;
        Class<?>[] types = constructor.getParameterTypes();
        java.lang.reflect.Parameter[] constructorParameters = constructor.getParameters();
        for (int index = 0; index < types.length; index++) {
            AlertParameter parameter = parameters.get(index);
            if (!types[index].getName().equals(parameter.getJavaType())
                    || !constructorParameters[index].getName().equals(parameter.getName()))
                return false;
        }
        return true;
    }
}
