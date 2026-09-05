package app.alertify.alerts.template;

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

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.model.AlertTemplateTagDefinition;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateKey;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;

/**
 * Discovers alert template implementations and synchronizes their declarative
 * metadata with the persistent catalog.
 */
@Service
public class AlertTemplateRegistrationService {

    static final String TEMPLATE_BASE_PACKAGE = "app.alertify.alerts.templates";

    private static final Logger log = LoggerFactory.getLogger(AlertTemplateRegistrationService.class);
    private static final Pattern TAG_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final AlertTemplateDefinitionRepository templateRepository;
    private final AlertTemplateParameterDefinitionRepository parameterRepository;
    private final ResourceLoader resourceLoader;

    public AlertTemplateRegistrationService(
        AlertTemplateDefinitionRepository templateRepository,
        AlertTemplateParameterDefinitionRepository parameterRepository,
        ResourceLoader resourceLoader
    ) {
        this.templateRepository = templateRepository;
        this.parameterRepository = parameterRepository;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Scans the shared template package and performs an idempotent metadata upsert.
     * Existing definitions that are no longer present in code are deliberately
     * retained because configured alerts may still reference them.
     */
    @Transactional
    public AlertTemplateRegistrationSummary scanAndRegister() {
        List<Class<?>> templateClasses = discoverTemplateClasses();
        int parameterCount = 0;

        for (Class<?> templateClass : templateClasses)
            parameterCount += register(templateClass);

        var summary = new AlertTemplateRegistrationSummary(templateClasses.size(), parameterCount);
        log.info("Alert template scan completed: templates={}, parameters={}", summary.templates(), summary.parameters());
        return summary;
    }

    private List<Class<?>> discoverTemplateClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AlertTemplate.class));

        return scanner.findCandidateComponents(TEMPLATE_BASE_PACKAGE).stream()
            .map(this::loadClass)
            .sorted(Comparator.comparing(Class::getName))
            .toList();
    }

    private Class<?> loadClass(BeanDefinition candidate) {
        String className = candidate.getBeanClassName();
        if (!StringUtils.hasText(className))
            throw new IllegalStateException("Discovered an alert template without a class name");

        try {
            return ClassUtils.forName(className, resourceLoader.getClassLoader());
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IllegalStateException("Could not load alert template " + className, exception);
        }
    }

    private int register(Class<?> templateClass) {
        validateTemplateClass(templateClass);

        AlertTemplate metadata = templateClass.getAnnotation(AlertTemplate.class);
        String templateKey = AlertTemplateKey.of(templateClass);
        AlertTemplateDefinition template = templateRepository.findByTemplateKey(templateKey)
            .orElseGet(() -> AlertTemplateDefinition.from(templateClass));
        template.synchronize(
            metadata.nameKey(), metadata.descriptionKey(), metadata.sourcePath(),
            metadata.capability(), Arrays.stream(metadata.tags())
                .map(AlertTemplateTagDefinition::from)
                .toList()
        );
        templateRepository.save(template);

        Map<String, AlertTemplateParameterDefinition> existingParameters = new LinkedHashMap<>();
        for (AlertTemplateParameterDefinition parameter
                : parameterRepository.findAllByTemplate_TemplateKey(templateKey)) {
            existingParameters.put(parameter.getParameterKey(), parameter);
        }

        List<Field> parameterFields = parameterFields(templateClass);
        for (Field field : parameterFields)
            synchronizeParameter(template, field, existingParameters.get(field.getName()));

        log.info("Registered alert template {} with {} parameters", templateKey, parameterFields.size());
        return parameterFields.size();
    }

    private void synchronizeParameter(
        AlertTemplateDefinition template,
        Field field,
        AlertTemplateParameterDefinition existing
    ) {
        AlertParameter metadata = field.getAnnotation(AlertParameter.class);
        List<String> options = List.of(metadata.options());
        String defaultValue = metadata.defaultValue().isEmpty()
            ? null
            : metadata.defaultValue();

        AlertTemplateParameterDefinition parameter = existing;
        if (parameter == null) {
            parameter = new AlertTemplateParameterDefinition(
                template,
                field.getName(),
                metadata.labelKey(),
                metadata.descriptionKey(),
                field.getType().getName(),
                options,
                metadata.bindingAllowed(),
                defaultValue,
                metadata.multiline(),
                metadata.order(),
                metadata.required()
            );
        } else {
            parameter.synchronize(
                metadata.labelKey(),
                metadata.descriptionKey(),
                field.getType().getName(),
                options,
                metadata.bindingAllowed(),
                defaultValue,
                metadata.multiline(),
                metadata.order(),
                metadata.required()
            );
        }
        parameterRepository.save(parameter);
    }

    private static void validateTemplateClass(Class<?> templateClass) {
        int modifiers = templateClass.getModifiers();
        if (templateClass.isInterface() || Modifier.isAbstract(modifiers)) {
            throw new IllegalStateException(
                "Alert template must be a concrete class: " + templateClass.getName()
            );
        }
        if (!AlertEvaluator.class.isAssignableFrom(templateClass)) {
            throw new IllegalStateException(
                "Alert template must implement AlertEvaluator: " + templateClass.getName()
            );
        }

        AlertTemplate metadata = templateClass.getAnnotation(AlertTemplate.class);
        requireText(metadata.nameKey(), "nameKey", templateClass);
        requireText(metadata.descriptionKey(), "descriptionKey", templateClass);
        validateTags(metadata, templateClass);
        validateSourcePath(metadata.sourcePath(), templateClass);

        List<Field> parameterFields = parameterFields(templateClass);
        validateConstructor(templateClass, parameterFields);
        for (Field field : parameterFields) {
            AlertParameter parameter = field.getAnnotation(AlertParameter.class);
            if (Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException(
                    "Alert parameter must not be static: " + templateClass.getName() + "." + field.getName()
                );
            }
            requireText(parameter.labelKey(), "labelKey", templateClass);
            requireText(parameter.descriptionKey(), "descriptionKey", templateClass);
            validateOptions(parameter, templateClass, field);
            if (!parameter.defaultValue().isEmpty()
                    && (!StringUtils.hasText(parameter.defaultValue())
                    || !parameter.defaultValue().equals(parameter.defaultValue().trim()))) {
                throw new IllegalStateException(
                    "Alert parameter defaultValue must be nonblank and trimmed: "
                        + templateClass.getName() + "." + field.getName()
                );
            }
            if (!parameter.bindingAllowed() && parameter.options().length == 0) {
                throw new IllegalStateException(
                    "Alert parameter options must not be empty when binding is disabled: "
                        + templateClass.getName() + "." + field.getName()
                );
            }
            if (!parameter.bindingAllowed()
                    && !parameter.defaultValue().isEmpty()
                    && !List.of(parameter.options()).contains(parameter.defaultValue())) {
                throw new IllegalStateException(
                    "Alert parameter defaultValue must be one of options when binding is disabled: "
                        + templateClass.getName() + "." + field.getName()
                );
            }
            if (parameter.order() < 0) {
                throw new IllegalStateException(
                    "Alert parameter order must not be negative: "
                        + templateClass.getName() + "." + field.getName()
                );
            }
        }
    }

    private static void validateTags(AlertTemplate metadata, Class<?> templateClass) {
        Set<String> nameKeys = new HashSet<>();
        for (var tag : metadata.tags()) {
            requireText(tag.nameKey(), "tag nameKey", templateClass);
            if (!nameKeys.add(tag.nameKey())) {
                throw new IllegalStateException(
                    "Alert template tag nameKey must be unique: " + templateClass.getName()
                );
            }
            if (!tag.color().isEmpty()
                    && (!tag.color().equals(tag.color().trim()) || !TAG_COLOR.matcher(tag.color()).matches())) {
                throw new IllegalStateException(
                    "Alert template tag color must be empty or use #RRGGBB format: " + templateClass.getName()
                );
            }
        }
    }

    private static void validateOptions(
        AlertParameter parameter,
        Class<?> templateClass,
        Field field
    ) {
        var uniqueOptions = new java.util.HashSet<String>();
        for (String option : parameter.options()) {
            if (!StringUtils.hasText(option) || !option.equals(option.trim())) {
                throw new IllegalStateException(
                    "Alert parameter options must be nonblank and trimmed: "
                        + templateClass.getName() + "." + field.getName()
                );
            }
            if (!uniqueOptions.add(option)) {
                throw new IllegalStateException(
                    "Alert parameter options must not contain duplicates: "
                        + templateClass.getName() + "." + field.getName()
                );
            }
        }
    }

    private static List<Field> parameterFields(Class<?> templateClass) {
        List<Field> fields = new ArrayList<>();
        for (Field field : templateClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(AlertParameter.class))
                fields.add(field);
        }
        fields.sort(
            Comparator.comparingInt((Field field) -> field.getAnnotation(AlertParameter.class).order())
                .thenComparing(Field::getName)
        );
        return fields;
    }

    private static void requireText(String value, String property, Class<?> templateClass) {
        if (!StringUtils.hasText(value) || !value.equals(value.trim())) {
            throw new IllegalStateException(
                "Alert template " + property + " must be nonblank and trimmed: "
                    + templateClass.getName()
            );
        }
    }

    private static void validateSourcePath(String sourcePath, Class<?> templateClass) {
        requireText(sourcePath, "sourcePath", templateClass);
        java.nio.file.Path path = java.nio.file.Path.of(sourcePath);
        if (path.isAbsolute() || sourcePath.contains("\\") || !sourcePath.endsWith(".java") || !path.equals(path.normalize()) || path.startsWith("..")) {
            throw new IllegalStateException(
                "Alert template sourcePath must be a relative normalized Java source path: "
                    + templateClass.getName()
            );
        }
    }

    private static void validateConstructor(Class<?> templateClass, List<Field> parameterFields) {
        Class<?>[] parameterTypes = parameterFields.stream().map(Field::getType).toArray(Class<?>[]::new);
        try {
            var constructor = templateClass.getDeclaredConstructor(parameterTypes);
            var constructorParameters = constructor.getParameters();
            for (int index = 0; index < constructorParameters.length; index++) {
                if (!constructorParameters[index].getName().equals(parameterFields.get(index).getName())) {
                    throw new IllegalStateException(
                        "Alert template constructor parameters must match ordered alert fields: " + templateClass.getName()
                    );
                }
            }
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                "Alert template must declare a constructor matching its ordered parameters: " + templateClass.getName(),
                exception
            );
        }
    }
}
