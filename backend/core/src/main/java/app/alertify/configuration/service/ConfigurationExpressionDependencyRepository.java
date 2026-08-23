package app.alertify.configuration.service;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Maintains the normalized dependency graph between expression
 * configurations. JDBC is used because this table represents graph edges and
 * has no independent domain lifecycle.
 */
@Repository
class ConfigurationExpressionDependencyRepository {

    private final JdbcTemplate jdbcTemplate;

    ConfigurationExpressionDependencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void replace(Long configurationId, Set<Long> referencedConfigurationIds) {
        jdbcTemplate.update(
                "delete from core.configuration_expression_dependencies where configuration_id = ?",
                configurationId
        );
        for (Long referencedConfigurationId : referencedConfigurationIds) {
            jdbcTemplate.update(
                    "insert into core.configuration_expression_dependencies (configuration_id, referenced_configuration_id) values (?, ?)",
                    configurationId, referencedConfigurationId
            );
        }
    }

    List<Long> findReferencedIds(Long configurationId) {
        return jdbcTemplate.queryForList(
                "select referenced_configuration_id from core.configuration_expression_dependencies where configuration_id = ? order by referenced_configuration_id",
                Long.class, configurationId
        );
    }

    List<String> findDependentNames(Long referencedConfigurationId) {
        return jdbcTemplate.queryForList(
                "select c.name from core.configuration_expression_dependencies d join core.configurations c on c.id = d.configuration_id where d.referenced_configuration_id = ? order by lower(c.name), c.name",
                String.class, referencedConfigurationId
        );
    }
}
