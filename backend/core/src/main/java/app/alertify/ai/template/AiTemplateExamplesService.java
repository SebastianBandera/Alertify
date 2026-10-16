package app.alertify.ai.template;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import app.alertify.alerts.template.TemplateParameterRules;
import app.alertify.alerts.template.TemplateParameterValidation;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import tools.jackson.databind.json.JsonMapper;

/** Curated examples are checked against current metadata before being returned. */
@Service
public class AiTemplateExamplesService {
    private final List<Recipe> recipes;

    public AiTemplateExamplesService() {
        try (var stream = new ClassPathResource("ai/template-examples.json").getInputStream()) {
            recipes = List.of(JsonMapper.builder().build().readValue(stream, Recipe[].class));
        } catch (IOException exception) {
            throw new IllegalStateException("Template configuration examples are unavailable", exception);
        }
    }

    public List<AiTemplateExample> examples(String templateKey, List<TemplateParameterRules> definitions) {
        return recipes.stream().filter(recipe -> recipe.templateKey().equals(templateKey)).map(recipe -> example(recipe, definitions)).toList();
    }

    private static AiTemplateExample example(Recipe recipe, List<TemplateParameterRules> definitions) {
        var keys = definitions.stream().map(TemplateParameterRules::key).collect(java.util.stream.Collectors.toSet());
        if (!keys.containsAll(recipe.text().keySet()) || !keys.containsAll(recipe.bindings().keySet())
                || recipe.text().keySet().stream().anyMatch(recipe.bindings()::containsKey))
            throw new IllegalStateException("Template example contains unknown or duplicate parameter keys: " + recipe.templateKey());

        List<AiTemplateExample.Parameter> parameters = new ArrayList<>();
        for (TemplateParameterRules definition : definitions) {
            Binding binding = recipe.bindings().get(definition.key());
            if (binding != null) {
                TemplateParameterValidation.source(definition, binding.source());
                parameters.add(new AiTemplateExample.Parameter(definition.key(), binding.source(), null, binding.requirement()));
                continue;
            }
            String text = recipe.text().getOrDefault(definition.key(), definition.defaultValue());
            if (text == null) {
                if (definition.required())
                    throw new IllegalStateException("Template example is missing a required parameter: " + recipe.templateKey() + "." + definition.key());

                continue;
            }
            TemplateParameterValidation.source(definition, AlertParameterSource.TEXT);
            TemplateParameterValidation.text(definition, text);
            parameters.add(new AiTemplateExample.Parameter(definition.key(), AlertParameterSource.TEXT, text, null));
        }
        List<String> notes = new ArrayList<>(recipe.notes());
        notes.add("Static configuration example, not a live execution result. Resolve each bindingRequirement before applying it. Use alertify_template_binding_candidates for reference IDs, then alertify_template_validate_parameters on the resolved list. For a TEXT requirement containing a resource ID, use that resource's search tool instead.");
        return new AiTemplateExample(recipe.name(), recipe.description(), List.copyOf(parameters), List.copyOf(notes), !recipe.bindings().isEmpty());
    }

    private record Recipe(String templateKey, String name, String description, Map<String, String> text, Map<String, Binding> bindings, List<String> notes) { }
    private record Binding(AlertParameterSource source, String requirement) { }
}
