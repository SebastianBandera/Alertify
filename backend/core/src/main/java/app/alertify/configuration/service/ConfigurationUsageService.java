package app.alertify.configuration.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.ConfigurationUsagesResponse;
import app.alertify.configuration.api.ConfigurationUsagesResponse.Usage;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;

/** Resolves consumers from persisted binding and expression dependency edges. */
@Service
public class ConfigurationUsageService {

    private static final String USAGES_SQL = """
            with recursive configuration_paths(root_id, configuration_id) as (
                select c.id, c.id from core.configurations c where c.id in (:ids)
                union
                select p.root_id, d.configuration_id
                from configuration_paths p
                join core.configuration_expression_dependencies d on d.referenced_configuration_id = p.configuration_id
            ), secret_paths(root_id, secret_id) as (
                select p.root_id, d.secret_id
                from configuration_paths p
                join secrets.secret_expression_dependencies d on d.referenced_configuration_id = p.configuration_id
                union
                select p.root_id, d.secret_id
                from secret_paths p
                join secrets.secret_expression_dependencies d on d.referenced_secret_id = p.secret_id
            ), usages(root_id, resource_type, resource_id, name, enabled, direct_binding) as (
                select p.root_id, 'CONFIGURATION', c.id, c.name, null::boolean, false
                from configuration_paths p join core.configurations c on c.id = p.configuration_id
                where p.configuration_id <> p.root_id
                union all
                select p.root_id, 'SECRET', s.id, s.name, null::boolean, false
                from secret_paths p join secrets.secrets s on s.id = p.secret_id
                union all
                select p.root_id, 'ALERT', a.id, a.name, a.enabled, p.configuration_id = p.root_id
                from configuration_paths p
                join core.alert_parameter_values v on v.configuration_id = p.configuration_id
                join core.alerts a on a.id = v.alert_id
                union all
                select p.root_id, 'ALERT', a.id, a.name, a.enabled, false
                from secret_paths p
                join core.alert_parameter_values v on v.secret_id = p.secret_id
                join core.alerts a on a.id = v.alert_id
                union all
                select p.root_id, 'PROCEDURE', proc.id, proc.name, proc.enabled, p.configuration_id = p.root_id
                from configuration_paths p
                join core.procedure_parameter_values v on v.configuration_id = p.configuration_id
                join core.procedures proc on proc.id = v.owner_procedure_id
                union all
                select p.root_id, 'PROCEDURE', proc.id, proc.name, proc.enabled, false
                from secret_paths p
                join core.procedure_parameter_values v on v.secret_id = p.secret_id
                join core.procedures proc on proc.id = v.owner_procedure_id
                union all
                select p.root_id, 'HOOK', h.id, h.name, h.enabled, false
                from secret_paths p join core.hooks h on h.token_secret_id = p.secret_id
            )
            select root_id, resource_type, resource_id, name, enabled, bool_or(direct_binding) as direct_binding
            from usages
            group by root_id, resource_type, resource_id, name, enabled
            order by root_id, resource_type, lower(name), name, resource_id
            """;

    private static final Comparator<Usage> USAGE_ORDER = Comparator.comparing(Usage::type)
            .thenComparing(Usage::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Usage::name)
            .thenComparing(Usage::id);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ApplicationConfigurationRepository configurationRepository;

    public ConfigurationUsageService(NamedParameterJdbcTemplate jdbcTemplate, ApplicationConfigurationRepository configurationRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.configurationRepository = configurationRepository;
    }

    @Transactional(readOnly = true)
    public ConfigurationUsagesResponse get(Long id) {
        if (!configurationRepository.existsById(id))
            throw new ResourceNotFoundException("Configuration " + id + " was not found");

        return getForIds(List.of(id)).getOrDefault(id, new ConfigurationUsagesResponse(0, List.of(), List.of()));
    }

    /** One graph query for the entire page, avoiding one query per configuration. */
    @Transactional(readOnly = true)
    public Map<Long, ConfigurationUsagesResponse> getForIds(Collection<Long> ids) {
        if (ids.isEmpty())
            return Map.of();

        Map<Long, List<Usage>> direct = new HashMap<>();
        Map<Long, List<Usage>> indirect = new HashMap<>();
        jdbcTemplate.query(USAGES_SQL, new MapSqlParameterSource("ids", ids), result -> {
            Long rootId = result.getLong("root_id");
            Boolean enabled = (Boolean) result.getObject("enabled");
            Usage usage = new Usage(result.getString("resource_type"), result.getLong("resource_id"),
                    result.getString("name"), enabled);
            Map<Long, List<Usage>> destination = result.getBoolean("direct_binding") ? direct : indirect;
            destination.computeIfAbsent(rootId, _ -> new ArrayList<>()).add(usage);
        });

        Map<Long, ConfigurationUsagesResponse> responses = new HashMap<>();
        for (Long id : ids) {
            List<Usage> directUsages = direct.getOrDefault(id, List.of()).stream().sorted(USAGE_ORDER).toList();
            List<Usage> indirectUsages = indirect.getOrDefault(id, List.of()).stream().sorted(USAGE_ORDER).toList();
            responses.put(id, new ConfigurationUsagesResponse(
                    directUsages.size() + indirectUsages.size(), directUsages, indirectUsages));
        }
        return responses;
    }
}
