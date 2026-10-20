package app.alertify.ai.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.support.CronExpression;

import app.alertify.alerts.template.ParameterValueTypeCompatibility;
import app.alertify.alerts.template.TemplateParameterRules;
import app.alertify.alerts.template.TemplateParameterValidation;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;
import app.alertify.worker.contract.GitCredentials;
import app.alertify.worker.contract.GitProvider;
import app.alertify.worker.contract.KubeconfigCredentials;
import app.alertify.worker.contract.UsernamePasswordCredentials;

class AiTemplateExamplesServiceTest {
    private final AiTemplateExamplesService examples = new AiTemplateExamplesService();

    @TestFactory
    Stream<DynamicTest> everyBuiltInExampleMatchesItsMetadataAndConstructsTheRealTemplate() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Class<?> type : templates()) {
            var rules = rules(type);
            List<AiTemplateExample> recipes = examples.examples(type.getName(), rules);
            assertThat(recipes).as("curated examples for %s", type.getName()).isNotEmpty();
            for (var example : recipes) {
                tests.add(DynamicTest.dynamicTest(type.getSimpleName() + ": " + example.name(), () -> {
                    Map<String, AiTemplateExample.Parameter> parameters = example.parameters().stream()
                            .collect(java.util.stream.Collectors.toMap(AiTemplateExample.Parameter::parameterKey, value -> value));
                    for (var rule : rules) {
                        var parameter = parameters.get(rule.key());
                        if (parameter == null) {
                            assertThat(rule.required()).isFalse();
                            continue;
                        }
                        TemplateParameterValidation.source(rule, parameter.source());
                        if (parameter.source() == AlertParameterSource.TEXT)
                            TemplateParameterValidation.text(rule, parameter.bindingRequirement() == null ? parameter.textValue() : "101");

                    }
                    var constructor = type.getConstructors()[0];
                    Object[] arguments = Arrays.stream(constructor.getParameters()).map(parameter -> {
                        var configured = parameters.get(parameter.getName());
                        String literal = configured == null ? null : configured.textValue();
                        if (configured != null && configured.bindingRequirement() != null)
                            return binding(parameter.getType());

                        return literal(parameter.getType(), literal);
                    }).toArray();
                    assertThat(constructor.newInstance(arguments)).isInstanceOf(type);
                    if (type.getSimpleName().equals("PlaywrightPageAlertTemplate"))
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(type, "parseCommands", parameters.get("steps").textValue());

                    if (type.getSimpleName().equals("WebRequestAlertTemplate")) {
                        for (String field : List.of("headersJson", "headersOverrideJson", "jsonAssertions", "responseBodyRegexes")) {
                            var configured = parameters.get(field);
                            if (configured == null || configured.bindingRequirement() != null)
                                continue;

                            String parser = switch (field) {
                                case "headersJson", "headersOverrideJson" -> "parseHeaders";
                                case "jsonAssertions" -> "parseJsonAssertions";
                                default -> "parsePatterns";
                            };
                            org.springframework.test.util.ReflectionTestUtils.invokeMethod(type, parser, configured.textValue());
                        }
                    }
                    assertThat(example.notes()).anyMatch(note -> note.contains("not a live execution result"));
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void unknownCustomTemplatesDoNotReceiveInventedExamples() {
        assertThat(examples.examples("app.alertify.alerts.templates.custom.Unknown", List.of())).isEmpty();
    }

    @Test
    void staleMetadataFailsInsteadOfSilentlyDroppingExampleParameters() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> examples.examples(
                "app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate", List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown or duplicate");
    }

    static List<Class<?>> templates() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AlertTemplate.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(ProcedureTemplate.class));
        List<Class<?>> types = new ArrayList<>();
        for (String base : List.of("app.alertify.alerts.templates", "app.alertify.procedures.templates")) {
            for (var candidate : scanner.findCandidateComponents(base)) {
                if (!candidate.getBeanClassName().contains(".custom."))
                    types.add(Class.forName(candidate.getBeanClassName()));

            }
        }
        return types;
    }

    static List<TemplateParameterRules> rules(Class<?> type) {
        List<TemplateParameterRules> result = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            var alert = field.getAnnotation(AlertParameter.class);
            var procedure = field.getAnnotation(ProcedureParameter.class);
            if (alert == null && procedure == null)
                continue;

            String javaType = field.getType().getName();
            List<String> config = alert == null ? List.of(procedure.allowedConfigurationValueTypes()) : List.of(alert.allowedConfigurationValueTypes());
            List<String> secret = alert == null ? List.of(procedure.allowedSecretValueTypes()) : List.of(alert.allowedSecretValueTypes());
            String defaultValue = alert == null ? procedure.defaultValue() : alert.defaultValue();
            result.add(new TemplateParameterRules(field.getName(), javaType, alert == null ? List.of(procedure.options()) : List.of(alert.options()),
                    alert == null ? procedure.bindingAllowed() : alert.bindingAllowed(), alert != null && alert.writableBindingRequired(),
                    alert == null ? procedure.required() : alert.required(), defaultValue.isEmpty() ? null : defaultValue,
                    alert == null ? List.of(procedure.allowedSources()) : List.of(alert.allowedSources()),
                    ParameterValueTypeCompatibility.effectiveAllowedConfigurationValueTypes(javaType, config, type.getName()),
                    ParameterValueTypeCompatibility.effectiveAllowedSecretValueTypes(javaType, secret, type.getName()), alert == null));
        }
        return List.copyOf(result);
    }

    private static Object binding(Class<?> type) {
        if (type == String.class)
            return "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        if (type == Long.class)
            return 101L;

        if (type == byte[].class)
            return new byte[0];

        if (type == DatabaseCredentials.class)
            return new DatabaseCredentials(DatabaseEngine.POSTGRESQL, "localhost", 5432, "example", "fixture", "fixture", null);

        if (type == GitCredentials.class)
            return new GitCredentials(GitProvider.GITLAB, "git.example.test", null, "fixture", null);

        if (type == UsernamePasswordCredentials.class)
            return new UsernamePasswordCredentials("fixture", "fixture");

        if (type == KubeconfigCredentials.class)
            return new KubeconfigCredentials("apiVersion: v1\n");

        return mock(type);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object literal(Class<?> type, String value) {
        if (value == null || type == String.class)
            return value;

        if (type == int.class || type == Integer.class)
            return Integer.valueOf(value);

        if (type == long.class || type == Long.class)
            return Long.valueOf(value);

        if (type == boolean.class || type == Boolean.class)
            return Boolean.valueOf(value);

        if (type == CronExpression.class)
            return CronExpression.parse(value);

        if (type == Duration.class)
            return Duration.parse(value);

        if (type == Instant.class)
            return Instant.parse(value);

        if (type.isEnum())
            return Enum.valueOf((Class<Enum>) type, value);

        throw new IllegalArgumentException("Unsupported example literal type: " + type.getName());
    }
}
