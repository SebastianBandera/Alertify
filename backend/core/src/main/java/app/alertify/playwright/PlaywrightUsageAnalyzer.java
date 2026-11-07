package app.alertify.playwright;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import app.alertify.configuration.service.ConfigurationExpressionService;

/** Read-only dependency discovery. Secret values and executable capabilities are never accessed. */
@Service
public class PlaywrightUsageAnalyzer {
    static final String SCRIPTS_SQL = """
            select a.id, a.name, a.enabled, coalesce(v.source, 'TEXT') as source,
                   case when v.id is null then p.default_value else v.text_value end as script,
                   v.configuration_id
            from core.alerts a
            join core.alert_templates t on t.id = a.alert_template_id
            join core.alert_template_parameters p on p.alert_template_id = t.id and p.parameter_key = 'steps'
            left join core.alert_parameter_values v on v.alert_id = a.id and v.template_parameter_id = p.id
            where t.template_key = 'app.alertify.alerts.templates.PlaywrightPageAlertTemplate'
            """;
    static final String NODES_SQL = """
            select 'CONFIGURATION' as type, id, name from core.configurations
            union all select 'SECRET', id, name from secrets.secrets
            union all select 'PROCEDURE', id, name from core.procedures
            """;
    static final String EDGES_SQL = """
            select 'CONFIGURATION' as owner_type, configuration_id as owner_id,
                   'CONFIGURATION' as target_type, referenced_configuration_id as target_id
            from core.configuration_expression_dependencies
            union all
            select 'SECRET', secret_id, case when referenced_secret_id is null then 'CONFIGURATION' else 'SECRET' end,
                   coalesce(referenced_secret_id, referenced_configuration_id)
            from secrets.secret_expression_dependencies
            union all
            select 'PROCEDURE', owner_procedure_id, source,
                   coalesce(configuration_id, secret_id, referenced_procedure_id)
            from core.procedure_parameter_values where source in ('CONFIGURATION', 'SECRET', 'PROCEDURE')
            """;

    private final JdbcTemplate jdbc;
    private final ConfigurationExpressionService configurations;

    public PlaywrightUsageAnalyzer(JdbcTemplate jdbc, ConfigurationExpressionService configurations) {
        this.jdbc = jdbc;
        this.configurations = configurations;
    }

    public Analysis analyze() {
        List<Script> scripts = jdbc.query(SCRIPTS_SQL, (row, _) -> new Script(row.getLong("id"), row.getString("name"),
                row.getBoolean("enabled"), row.getString("source"), row.getString("script"), (Long) row.getObject("configuration_id")));
        if (scripts.isEmpty())
            return new Analysis(Map.of(), List.of());

        List<Resource> resources = jdbc.query(NODES_SQL, (row, _) -> new Resource(new Key(row.getString("type"), row.getLong("id")), row.getString("name")));
        List<Edge> edges = jdbc.query(EDGES_SQL, (row, _) -> new Edge(new Key(row.getString("owner_type"), row.getLong("owner_id")),
                new Key(row.getString("target_type"), row.getLong("target_id"))));
        Map<Long, String> configurationNames = new HashMap<>();
        for (Resource resource : resources) {
            if (resource.key().type().equals("CONFIGURATION"))
                configurationNames.put(resource.key().id(), resource.name());

        }
        return analyze(scripts, resources, edges, id -> configurations.getResolvedValueByName(configurationNames.get(id)));
    }

    static Analysis analyze(List<Script> scripts, List<Resource> resources, List<Edge> edges, java.util.function.Function<Long, String> resolveConfiguration) {
        Map<String, Key> names = new HashMap<>();
        resources.forEach(resource -> names.put(nameKey(resource.key().type(), resource.name()), resource.key()));
        Map<Key, List<Key>> dependencies = new HashMap<>();
        edges.forEach(edge -> dependencies.computeIfAbsent(edge.owner(), _ -> new ArrayList<>()).add(edge.target()));
        Map<Long, ParsedScript> configurationReferences = new HashMap<>();
        Map<Key, Set<Key>> closures = new HashMap<>();
        Map<Key, Map<Long, Consumer>> usages = new HashMap<>();
        Set<PlaywrightUsageIssue> issues = new HashSet<>();
        for (Script script : scripts) {
            ParsedScript parsed;
            switch (script.source()) {
                case "SECRET" -> {
                    issues.add(issue(script, "SECRET_SCRIPT_EXCLUDED"));
                    continue;
                }
                case "TEXT" -> parsed = parse(script.text());
                case "CONFIGURATION" -> parsed = configurationReferences.computeIfAbsent(script.configurationId(), id -> {
                    String resolved;
                    try { resolved = resolveConfiguration.apply(id); }
                    catch (RuntimeException failure) {
                        return new ParsedScript(List.of(), "SCRIPT_UNAVAILABLE");
                    }
                    return parse(resolved);
                });
                default -> {
                    issues.add(issue(script, "SCRIPT_UNAVAILABLE"));
                    continue;
                }
            }
            if (parsed.warning() != null)
                issues.add(issue(script, parsed.warning()));

            for (var reference : parsed.references()) {
                Key target = names.get(nameKey(reference.source().name(), reference.name()));
                if (target == null) {
                    issues.add(issue(script, "REFERENCE_UNAVAILABLE"));
                    continue;
                }
                for (Key dependency : closures.computeIfAbsent(target, key -> closure(key, dependencies))) {
                    if (dependency.type().equals("PROCEDURE"))
                        continue;

                    boolean direct = dependency.equals(target);
                    Consumer consumer = new Consumer(script.id(), script.name(), script.enabled(), direct);
                    usages.computeIfAbsent(dependency, _ -> new HashMap<>()).merge(script.id(), consumer,
                            (previous, current) -> previous.direct() ? previous : current);
                }
            }
        }
        return new Analysis(usages, issues.stream().sorted(Comparator.comparing(PlaywrightUsageIssue::code)
                .thenComparing(PlaywrightUsageIssue::alertName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(PlaywrightUsageIssue::alertId)).toList());
    }

    private static PlaywrightUsageIssue issue(Script script, String code) {
        return new PlaywrightUsageIssue(code, script.id(), script.name());
    }

    private static ParsedScript parse(String script) {
        try { return new ParsedScript(PlaywrightPageEngine.requiredValues(script), null); }
        catch (IllegalArgumentException failure) {
            // A parser error can contain script text. Return only a stable, non-sensitive code.
            return new ParsedScript(List.of(), "INVALID_SCRIPT");
        }
    }

    private static Set<Key> closure(Key root, Map<Key, List<Key>> dependencies) {
        Set<Key> visited = new HashSet<>();
        ArrayDeque<Key> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Key current = pending.removeFirst();
            if (visited.add(current))
                pending.addAll(dependencies.getOrDefault(current, List.of()));

        }
        return visited;
    }

    private static String nameKey(String type, String name) {
        return type + ":" + name.toLowerCase(Locale.ROOT);
    }

    record Key(String type, long id) { }
    record Resource(Key key, String name) { }
    record Edge(Key owner, Key target) { }
    record Script(long id, String name, boolean enabled, String source, String text, Long configurationId) { }
    record ParsedScript(List<PlaywrightPageEngine.ValueReference> references, String warning) { }
    public record Consumer(long id, String name, boolean enabled, boolean direct) { }
    public record Analysis(Map<Key, Map<Long, Consumer>> usages, List<PlaywrightUsageIssue> issues) {
        public boolean complete() { return issues.isEmpty(); }
        public List<String> warnings() { return issues.stream().map(PlaywrightUsageIssue::code).distinct().sorted().toList(); }
        public List<Consumer> consumers(String type, long id) {
            return List.copyOf(usages.getOrDefault(new Key(type, id), Map.of()).values());
        }
    }
}
