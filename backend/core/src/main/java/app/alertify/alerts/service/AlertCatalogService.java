package app.alertify.alerts.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertBindingOptionResponse;
import app.alertify.alerts.api.AlertBindingOptionsResponse;
import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;

/**
 * Read-only catalog behind the alert editor: the registered templates with
 * their parameters, and the configurations, secrets and procedures a parameter
 * may be bound to. Internal configuration entries are never offered as binding
 * options.
 */
@Service
public class AlertCatalogService {

    private final AlertTemplateDefinitionRepository templateRepository;
    private final AlertTemplateParameterDefinitionRepository parameterRepository;
    private final AlertRepository alertRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final ProcedureRepository procedureRepository;
    private final ApplicationEventLogger eventLogger;

    public AlertCatalogService(AlertTemplateDefinitionRepository templateRepository, AlertTemplateParameterDefinitionRepository parameterRepository, AlertRepository alertRepository, ApplicationConfigurationRepository configurationRepository, ApplicationSecretRepository secretRepository, ProcedureRepository procedureRepository, ApplicationEventLogger eventLogger) {
        this.templateRepository = templateRepository;
        this.parameterRepository = parameterRepository;
        this.alertRepository = alertRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.procedureRepository = procedureRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public List<AlertTemplateResponse> templates() {
        Map<Long, Long> alertCounts = alertRepository.countAlertsByTemplate()
                .stream()
                .collect(Collectors.toMap(
                        AlertRepository.TemplateAlertCount::getTemplateId,
                        AlertRepository.TemplateAlertCount::getAlertCount
                ));
        List<AlertTemplateResponse> result = templateRepository
                .findAll(Sort.by(Sort.Direction.ASC, "templateKey"))
                .stream()
                .map(template -> AlertMapper.toTemplate(
                        template,
                        parameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(template.getId()),
                        alertCounts.getOrDefault(template.getId(), 0L)
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
                .map(AlertCatalogService::toBindingOption)
                .toList();
        List<AlertBindingOptionResponse> secrets = secretRepository
                .findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(secret -> new AlertBindingOptionResponse(secret.getId(), secret.getName(), secret.getDescription()))
                .toList();
        List<AlertBindingOptionResponse> procedures = procedureRepository
                .findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(procedure -> new AlertBindingOptionResponse(
                        procedure.getId(), procedure.getName(), procedure.getDescription(), procedure.isEnabled()))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configurationCount", configurations.size());
        data.put("secretCount", secrets.size());
        data.put("procedureCount", procedures.size());
        eventLogger.success("ALERT_BINDING_CATALOG_ACCESSED", data);
        return new AlertBindingOptionsResponse(configurations, secrets, procedures);
    }

    private static AlertBindingOptionResponse toBindingOption(ApplicationConfiguration configuration) {
        return new AlertBindingOptionResponse(
                configuration.getId(), configuration.getName(), configuration.getDescription()
        );
    }
}
