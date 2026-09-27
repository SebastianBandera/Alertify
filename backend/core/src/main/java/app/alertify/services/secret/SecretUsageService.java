package app.alertify.services.secret;

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
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.secret.api.SecretUsagesResponse;
import app.alertify.secret.api.SecretUsagesResponse.Usage;

/** Resolves consumers from persisted secret bindings and expression dependency edges. */
@Service
public class SecretUsageService {

    private static final String USAGES_SQL = """
            with recursive secret_paths(root_id, secret_id) as (
                select s.id, s.id from secrets.secrets s where s.id in (:ids)
                union
                select p.root_id, d.secret_id
                from secret_paths p
                join secrets.secret_expression_dependencies d on d.referenced_secret_id = p.secret_id
            ), usages(root_id, resource_type, resource_id, name, enabled, direct_binding) as (
                select p.root_id, 'SECRET', s.id, s.name, null::boolean, false
                from secret_paths p join secrets.secrets s on s.id = p.secret_id
                where p.secret_id <> p.root_id
                union all
                select p.root_id, 'ALERT', a.id, a.name, a.enabled, p.secret_id = p.root_id
                from secret_paths p
                join core.alert_parameter_values v on v.secret_id = p.secret_id
                join core.alerts a on a.id = v.alert_id
                union all
                select p.root_id, 'PROCEDURE', proc.id, proc.name, proc.enabled, p.secret_id = p.root_id
                from secret_paths p
                join core.procedure_parameter_values v on v.secret_id = p.secret_id
                join core.procedures proc on proc.id = v.owner_procedure_id
                union all
                select p.root_id, 'HOOK', h.id, h.name, h.enabled, p.secret_id = p.root_id
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
    private final ApplicationSecretRepository secretRepository;

    public SecretUsageService(NamedParameterJdbcTemplate jdbcTemplate, ApplicationSecretRepository secretRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretRepository = secretRepository;
    }

    @Transactional(readOnly = true)
    public SecretUsagesResponse get(Long id) {
        if (!secretRepository.existsById(id))
            throw new ResourceNotFoundException("Secret " + id + " was not found");

        return getForIds(List.of(id)).getOrDefault(id, new SecretUsagesResponse(0, List.of(), List.of()));
    }

    /** One graph query for the entire page, avoiding one query per secret. */
    @Transactional(readOnly = true)
    public Map<Long, SecretUsagesResponse> getForIds(Collection<Long> ids) {
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

        Map<Long, SecretUsagesResponse> responses = new HashMap<>();
        for (Long id : ids) {
            List<Usage> directUsages = direct.getOrDefault(id, List.of()).stream().sorted(USAGE_ORDER).toList();
            List<Usage> indirectUsages = indirect.getOrDefault(id, List.of()).stream().sorted(USAGE_ORDER).toList();
            responses.put(id, new SecretUsagesResponse(
                    directUsages.size() + indirectUsages.size(), directUsages, indirectUsages));
        }
        return responses;
    }
}
