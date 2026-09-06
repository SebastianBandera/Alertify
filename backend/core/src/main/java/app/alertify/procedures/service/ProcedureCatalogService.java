package app.alertify.procedures.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.api.ProcedureBindingOptionResponse;
import app.alertify.procedures.api.ProcedureBindingOptionsResponse;
import app.alertify.procedures.api.ProcedureTemplateResponse;

/**
 * Read-only catalog behind the procedure editor: the registered templates with
 * their parameters, and the configurations, secrets and procedures a parameter
 * may be bound to. Internal configuration entries are never offered as binding
 * options.
 */
@Service
public class ProcedureCatalogService {

    private static final String HIDDEN_CONFIGURATION = "KEY_PART";
    private final ProcedureTemplateDefinitionRepository templateRepository;
    private final ProcedureTemplateParameterDefinitionRepository parameterRepository;
    private final ProcedureRepository procedureRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final ApplicationEventLogger eventLogger;

    public ProcedureCatalogService(ProcedureTemplateDefinitionRepository templateRepository,
            ProcedureTemplateParameterDefinitionRepository parameterRepository,
            ProcedureRepository procedureRepository, ApplicationConfigurationRepository configurationRepository,
            ApplicationSecretRepository secretRepository, ApplicationEventLogger eventLogger) {
        this.templateRepository = templateRepository;
        this.parameterRepository = parameterRepository;
        this.procedureRepository = procedureRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public List<ProcedureTemplateResponse> templates() {
        Map<Long, Long> counts = procedureRepository.countProceduresByTemplate().stream().collect(Collectors.toMap(
                ProcedureRepository.TemplateProcedureCount::getTemplateId,
                ProcedureRepository.TemplateProcedureCount::getProcedureCount));
        List<ProcedureTemplateResponse> result = templateRepository.findAll(Sort.by("templateKey")).stream()
                .map(template -> ProcedureMapper.toTemplate(template,
                        parameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(template.getId()),
                        counts.getOrDefault(template.getId(), 0L)))
                .toList();
        eventLogger.success("PROCEDURE_TEMPLATE_CATALOG_VIEWED", Map.of("templateCount", result.size()));
        return result;
    }

    @Transactional(readOnly = true)
    public ProcedureBindingOptionsResponse bindingOptions() {
        var configurations = configurationRepository.findAll(Sort.by("name")).stream()
                .filter(value -> !HIDDEN_CONFIGURATION.equalsIgnoreCase(value.getName()))
                .map(value -> new ProcedureBindingOptionResponse(value.getId(), value.getName(), value.getDescription(), true))
                .toList();
        var secrets = secretRepository.findAll(Sort.by("name")).stream()
                .map(value -> new ProcedureBindingOptionResponse(value.getId(), value.getName(), value.getDescription(), true))
                .toList();
        var procedures = procedureRepository.findAll(Sort.by("name")).stream()
                .map(value -> new ProcedureBindingOptionResponse(value.getId(), value.getName(), value.getDescription(), value.isEnabled()))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configurationCount", configurations.size());
        data.put("secretCount", secrets.size());
        data.put("procedureCount", procedures.size());
        eventLogger.success("PROCEDURE_BINDING_CATALOG_ACCESSED", data);
        return new ProcedureBindingOptionsResponse(configurations, secrets, procedures);
    }
}
