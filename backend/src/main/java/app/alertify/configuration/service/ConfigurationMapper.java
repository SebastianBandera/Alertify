package app.alertify.configuration.service;

import java.util.LinkedHashSet;
import java.util.Set;

import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.api.TagResponse;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.Tag;

final class ConfigurationMapper {

    private ConfigurationMapper() {
    }

    static ConfigurationResponse toResponse(ApplicationConfiguration configuration) {
        Set<TagResponse> tags = configuration.getTags().stream()
            .sorted(java.util.Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
            .map(ConfigurationMapper::toResponse)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean valueHidden = SystemConfigurationPolicy.isValueHidden(configuration.getName());

        return new ConfigurationResponse(
            configuration.getId(), configuration.getVersion(), configuration.getName(),
            configuration.getDescription(), configuration.getValueType(),
            valueHidden ? null : configuration.getValue().deepCopy(), valueHidden, tags,
            SystemConfigurationPolicy.isSystemManaged(configuration.getName()),
            SystemConfigurationPolicy.isDeletable(configuration.getName()),
            SystemConfigurationPolicy.warning(configuration.getName()),
            configuration.getCreatedAt(), configuration.getUpdatedAt()
        );
    }

    static TagResponse toResponse(Tag tag) {
        return new TagResponse(
            tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(), tag.getColor(),
            tag.getCreatedAt(), tag.getUpdatedAt()
        );
    }
}
