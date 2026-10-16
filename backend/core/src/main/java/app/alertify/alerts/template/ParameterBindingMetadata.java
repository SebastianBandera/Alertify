package app.alertify.alerts.template;

import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;

/** Closed JPA projections deliberately excluding values, encrypted bytes and expressions. */
public final class ParameterBindingMetadata {
    private ParameterBindingMetadata() { }

    public interface Named {
        Long getId();
        String getName();
        String getDescription();
    }

    public interface Configuration extends Named {
        ConfigurationValueType getValueType();
        boolean isWritable();
    }

    public interface Secret extends Named {
        SecretValueType getValueType();
        boolean isWritable();
    }

    public interface Executable extends Named {
        boolean isEnabled();
    }
}
