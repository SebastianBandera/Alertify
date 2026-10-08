package app.alertify.pipes.service;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.configuration.service.ConfigurationExpressionParser.ExpressionScope;
import app.alertify.configuration.service.ConfigurationExpressionParser.ParsedExpression;
import app.alertify.configuration.service.ConfigurationExpressionUtilityResolver;
import app.alertify.configuration.service.ExpressionEvaluator;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PipeBindingExpressionService {
    private static final String JSON_STRING = "JSON_STRING";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConfigurationExpressionParser parser;
    private final ConfigurationExpressionUtilityResolver utilities;

    public PipeBindingExpressionService(ConfigurationExpressionParser parser, ConfigurationExpressionUtilityResolver utilities) {
        this.parser = parser;
        this.utilities = utilities;
    }

    public void validate(String expression) {
        if (expression == null || expression.isBlank())
            return;

        parse(expression);
    }

    public List<String> functionNames() {
        return Stream.concat(utilities.functionNames().stream(), Stream.of(JSON_STRING)).toList();
    }

    public String evaluate(String expression, String value) {
        if (expression == null || expression.isBlank())
            return value;

        try {
            var parsed = parse(expression);
            return ExpressionEvaluator.evaluate(parsed, (reference, argument, _) -> switch (reference.type()) {
                case PIPE -> value;
                case UTILITY -> reference.name().equals(JSON_STRING) ? JSON.writeValueAsString(argument) : utilities.apply(reference.name(), argument);
                case CONFIGURATION, SECRET, ENVIRONMENT -> throw new InvalidConfigurationExpressionException("Pipe binding expressions cannot access external values");
            }, 0);
        } catch (RuntimeException exception) {
            throw new InvalidConfigurationExpressionException("Pipe binding expression evaluation failed");
        }
    }

    private ParsedExpression parse(String expression) {
        ParsedExpression parsed = parser.parse(expression, ExpressionScope.PIPE_BINDING);
        if (!parsed.pipeNames().contains("VALUE"))
            throw new InvalidConfigurationExpressionException("Pipe binding expression must reference {{pipe.VALUE}}");

        parsed.utilityFunctionNames().stream().filter(name -> !name.equals(JSON_STRING))
                .forEach(name -> utilities.ensureSupported(name, true));
        return parsed;
    }
}
