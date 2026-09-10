package app.alertify.systemconfiguration.service;

import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.systemconfiguration.api.SystemConfigurationResponse;

final class SystemConfigurationMapper {

    private SystemConfigurationMapper() {
    }

    static SystemConfigurationResponse toResponse(SystemConfiguration configuration) {
        return new SystemConfigurationResponse(
                configuration.getId(), configuration.getVersion(), configuration.getName(),
                configuration.isValueHidden() ? null : configuration.getValue().deepCopy(),
                configuration.isValueHidden(),
                configuration.getCreatedAt(), configuration.getUpdatedAt()
        );
    }
}
