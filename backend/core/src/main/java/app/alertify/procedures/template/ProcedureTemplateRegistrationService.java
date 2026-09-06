package app.alertify.procedures.template;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.Procedure;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.procedures.model.ProcedureTemplateTagDefinition;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateKey;

/** Discovers and synchronizes procedure template metadata at startup. */
@Service
public class ProcedureTemplateRegistrationService {

    static final String TEMPLATE_BASE_PACKAGE = "app.alertify.procedures.templates";
    private static final Pattern TAG_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcedureTemplateRegistrationService.class);

    private final ProcedureTemplateDefinitionRepository templateRepository;
    private final ProcedureTemplateParameterDefinitionRepository parameterRepository;
    private final ResourceLoader resourceLoader;

    public ProcedureTemplateRegistrationService(ProcedureTemplateDefinitionRepository templateRepository,
            ProcedureTemplateParameterDefinitionRepository parameterRepository, ResourceLoader resourceLoader) {
        this.templateRepository = templateRepository;
        this.parameterRepository = parameterRepository;
        this.resourceLoader = resourceLoader;
    }

    @Transactional
    public int scanAndRegister() {
        List<Class<?>> classes = discover();
        int parameterCount = 0;
        for (Class<?> templateClass : classes)
            parameterCount += register(templateClass);

        LOGGER.info("Procedure template scan completed: templates={}, parameters={}", classes.size(), parameterCount);
        return classes.size();
    }

    private List<Class<?>> discover() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ProcedureTemplate.class));
        return scanner.findCandidateComponents(TEMPLATE_BASE_PACKAGE).stream()
                .map(this::load)
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    private Class<?> load(BeanDefinition definition) {
        String className = definition.getBeanClassName();
        if (!StringUtils.hasText(className))
            throw new IllegalStateException("Discovered a procedure template without a class name");

        try {
            return ClassUtils.forName(className, resourceLoader.getClassLoader());
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IllegalStateException("Could not load procedure template " + className, exception);
        }
    }

    private int register(Class<?> templateClass) {
        validate(templateClass);
        ProcedureTemplate metadata = templateClass.getAnnotation(ProcedureTemplate.class);
        String templateKey = ProcedureTemplateKey.of(templateClass);
        ProcedureTemplateDefinition template = templateRepository.findByTemplateKey(templateKey)
                .orElseGet(() -> ProcedureTemplateDefinition.from(templateClass));
        template.synchronize(metadata.nameKey(), metadata.descriptionKey(), metadata.sourcePath(),
                metadata.capability(), metadata.sensitiveResult(),
                Arrays.stream(metadata.tags()).map(ProcedureTemplateTagDefinition::from).toList());
        templateRepository.save(template);

        Map<String, ProcedureTemplateParameterDefinition> existing = new LinkedHashMap<>();
        for (ProcedureTemplateParameterDefinition value : parameterRepository.findAllByTemplate_TemplateKey(templateKey))
            existing.put(value.getParameterKey(), value);

        List<Field> fields = parameterFields(templateClass);
        for (Field field : fields) {
            ProcedureParameter parameter = field.getAnnotation(ProcedureParameter.class);
            ProcedureTemplateParameterDefinition definition = existing.get(field.getName());
            String defaultValue = parameter.defaultValue().isEmpty() ? null : parameter.defaultValue();
            List<AlertParameterSource> allowedSources = List.of(parameter.allowedSources());
            if (definition == null) {
                definition = new ProcedureTemplateParameterDefinition(
                        template, field.getName(), parameter.labelKey(), parameter.descriptionKey(),
                        field.getType().getName(), List.of(parameter.options()), parameter.bindingAllowed(),
                        defaultValue, parameter.multiline(), parameter.order(), parameter.required(), allowedSources
                );
            } else {
                definition.synchronize(parameter.labelKey(), parameter.descriptionKey(), field.getType().getName(),
                        List.of(parameter.options()), parameter.bindingAllowed(), defaultValue,
                        parameter.multiline(), parameter.order(), parameter.required(), allowedSources);
            }
            parameterRepository.save(definition);
        }
        return fields.size();
    }

    private static void validate(Class<?> templateClass) {
        int modifiers = templateClass.getModifiers();
        if (templateClass.isInterface() || Modifier.isAbstract(modifiers)
                || !ProcedureEvaluator.class.isAssignableFrom(templateClass))
            throw new IllegalStateException("Procedure template must be a concrete ProcedureEvaluator: " + templateClass.getName());

        ProcedureTemplate template = templateClass.getAnnotation(ProcedureTemplate.class);
        requireText(template.nameKey(), "nameKey", templateClass);
        requireText(template.descriptionKey(), "descriptionKey", templateClass);
        validateSourcePath(template.sourcePath(), templateClass);
        Set<String> tagKeys = new HashSet<>();
        Arrays.stream(template.tags()).forEach(tag -> {
            requireText(tag.nameKey(), "tag nameKey", templateClass);
            if (!tagKeys.add(tag.nameKey()))
                throw new IllegalStateException("Procedure template tag keys must be unique: " + templateClass.getName());

            if (!tag.color().isEmpty() && !TAG_COLOR.matcher(tag.color()).matches())
                throw new IllegalStateException("Procedure template tag color must use #RRGGBB: " + templateClass.getName());
        });

        List<Field> fields = parameterFields(templateClass);
        Class<?>[] types = fields.stream().map(Field::getType).toArray(Class<?>[]::new);
        try {
            var constructor = templateClass.getDeclaredConstructor(types);
            var parameters = constructor.getParameters();
            for (int index = 0; index < parameters.length; index++) {
                if (!parameters[index].getName().equals(fields.get(index).getName()))
                    throw new IllegalStateException("Procedure constructor parameters must match ordered fields: " + templateClass.getName());
            }
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Procedure template must declare a constructor matching its parameters: " + templateClass.getName(), exception);
        }

        for (Field field : fields)
            validateParameter(templateClass, field);
    }

    private static void validateParameter(Class<?> templateClass, Field field) {
        ProcedureParameter parameter = field.getAnnotation(ProcedureParameter.class);
        if (Modifier.isStatic(field.getModifiers()))
            throw new IllegalStateException("Procedure parameter must not be static: " + templateClass.getName() + "." + field.getName());

        requireText(parameter.labelKey(), "parameter labelKey", templateClass);
        requireText(parameter.descriptionKey(), "parameter descriptionKey", templateClass);
        if (parameter.order() < 0)
            throw new IllegalStateException("Procedure parameter order must not be negative: " + field.getName());

        List<String> options = List.of(parameter.options());
        if (!parameter.bindingAllowed() && options.isEmpty())
            throw new IllegalStateException("Procedure parameter options are required when binding is disabled: " + field.getName());

        if (!parameter.defaultValue().isEmpty() && !parameter.bindingAllowed() && !options.contains(parameter.defaultValue()))
            throw new IllegalStateException("Procedure parameter default must be one of options: " + field.getName());

        Set<AlertParameterSource> sources = Set.of(parameter.allowedSources());
        boolean procedureField = field.getType() == Procedure.class;
        if (procedureField && (!sources.equals(Set.of(AlertParameterSource.PROCEDURE))
                || !parameter.defaultValue().isEmpty() || !parameter.bindingAllowed()))
            throw new IllegalStateException("Procedure handle parameters must allow only PROCEDURE binding: " + field.getName());

        if (!procedureField && sources.contains(AlertParameterSource.PROCEDURE))
            throw new IllegalStateException("Only Procedure fields may allow PROCEDURE source: " + field.getName());
    }

    private static List<Field> parameterFields(Class<?> templateClass) {
        List<Field> fields = new ArrayList<>();
        for (Field field : templateClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(ProcedureParameter.class))
                fields.add(field);
        }
        fields.sort(Comparator.comparingInt((Field field) -> field.getAnnotation(ProcedureParameter.class).order())
                .thenComparing(Field::getName));
        return fields;
    }

    private static void requireText(String value, String name, Class<?> type) {
        if (!StringUtils.hasText(value) || !value.equals(value.trim()))
            throw new IllegalStateException("Procedure template " + name + " must be nonblank and trimmed: " + type.getName());
    }

    private static void validateSourcePath(String value, Class<?> type) {
        requireText(value, "sourcePath", type);
        java.nio.file.Path path = java.nio.file.Path.of(value);
        if (path.isAbsolute() || value.contains("\\") || !value.endsWith(".java")
                || !path.equals(path.normalize()) || path.startsWith(".."))
            throw new IllegalStateException("Procedure sourcePath must be a normalized relative Java path: " + type.getName());
    }
}
