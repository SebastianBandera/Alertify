package app.alertify.alerts.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertBindingOptionResponse;
import app.alertify.alerts.api.AlertBindingOptionsResponse;
import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class AlertCatalogService {

    private static final String HIDDEN_CONFIGURATION = "KEY_PART";

    private final AlertTemplateDefinitionRepository templateRepository;
    private final AlertTemplateParameterDefinitionRepository parameterRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final ApplicationEventLogger eventLogger;

    public AlertCatalogService(AlertTemplateDefinitionRepository templateRepository, AlertTemplateParameterDefinitionRepository parameterRepository, ApplicationConfigurationRepository configurationRepository, ApplicationSecretRepository secretRepository, ApplicationEventLogger eventLogger) {
        this.templateRepository = templateRepository;
        this.parameterRepository = parameterRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public List<AlertTemplateResponse> templates() {
        List<AlertTemplateResponse> result = templateRepository
                .findAll(Sort.by(Sort.Direction.ASC, "templateKey"))
                .stream()
                .map(template -> AlertMapper.toTemplate(
                        template,
                        parameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(template.getId())
                ))
                .toList();

        eventLogger.success("ALERT_TEMPLATE_CATALOG_VIEWED", Map.of("templateCount", result.size()));
        return result;
    }

    @Transactional(readOnly = true)
    public AlertBindingOptionsResponse bindingOptions() {
        List<AlertBindingOptionResponse> configurations = configurationRepository
                .findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .filter(configuration -> !HIDDEN_CONFIGURATION.equalsIgnoreCase(configuration.getName()))
                .map(AlertCatalogService::toBindingOption)
                .toList();
        List<AlertBindingOptionResponse> secrets = secretRepository
                .findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(secret -> new AlertBindingOptionResponse(secret.getId(), secret.getName(), secret.getDescription()))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configurationCount", configurations.size());
        data.put("secretCount", secrets.size());
        eventLogger.success("ALERT_BINDING_CATALOG_ACCESSED", data);
        return new AlertBindingOptionsResponse(configurations, secrets);
    }

    private static AlertBindingOptionResponse toBindingOption(ApplicationConfiguration configuration) {
        return new AlertBindingOptionResponse(
                configuration.getId(), configuration.getName(), configuration.getDescription()
        );
    }
}
