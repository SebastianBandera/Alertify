package app.alertify.jpa.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "configuration_binary_values", schema = "core")
public class ConfigurationBinaryValue {
    @Id
    @Column(name = "configuration_id")
    private Long configurationId;

    @Column(name = "zip_value", nullable = false, columnDefinition = "bytea")
    private byte[] zipValue;

    protected ConfigurationBinaryValue() { }

    public ConfigurationBinaryValue(Long configurationId, byte[] zipValue) {
        this.configurationId = Objects.requireNonNull(configurationId);
        replace(zipValue);
    }

    public Long getConfigurationId() { return configurationId; }
    public byte[] getZipValue() { return zipValue.clone(); }
    public void replace(byte[] value) { zipValue = Objects.requireNonNull(value).clone(); }
}
