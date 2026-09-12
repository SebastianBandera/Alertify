package app.alertify.services.secret;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Maintains the dependency edges of expression secrets towards other secrets
 * and towards configurations. Only identifiers are stored; the expression text
 * itself stays encrypted with the secret value.
 */
@Repository
public class SecretExpressionDependencyRepository {

    private final JdbcTemplate jdbcTemplate;

    SecretExpressionDependencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void replace(Long secretId, Set<Long> referencedSecretIds, Set<Long> referencedConfigurationIds) {
        jdbcTemplate.update(
                "delete from secrets.secret_expression_dependencies where secret_id = ?",
                secretId
        );
        for (Long referencedSecretId : referencedSecretIds) {
            jdbcTemplate.update(
                    "insert into secrets.secret_expression_dependencies (secret_id, referenced_secret_id) values (?, ?)",
                    secretId, referencedSecretId
            );
        }
        for (Long referencedConfigurationId : referencedConfigurationIds) {
            jdbcTemplate.update(
                    "insert into secrets.secret_expression_dependencies (secret_id, referenced_configuration_id) values (?, ?)",
                    secretId, referencedConfigurationId
            );
        }
    }

    public List<Long> findReferencedSecretIds(Long secretId) {
        return jdbcTemplate.queryForList(
                "select referenced_secret_id from secrets.secret_expression_dependencies where secret_id = ? and referenced_secret_id is not null order by referenced_secret_id",
                Long.class, secretId
        );
    }

    public List<String> findDependentSecretNames(Long referencedSecretId) {
        return jdbcTemplate.queryForList(
                "select s.name from secrets.secret_expression_dependencies d join secrets.secrets s on s.id = d.secret_id where d.referenced_secret_id = ? order by lower(s.name), s.name",
                String.class, referencedSecretId
        );
    }

    public List<String> findDependentSecretNamesForConfiguration(Long referencedConfigurationId) {
        return jdbcTemplate.queryForList(
                "select s.name from secrets.secret_expression_dependencies d join secrets.secrets s on s.id = d.secret_id where d.referenced_configuration_id = ? order by lower(s.name), s.name",
                String.class, referencedConfigurationId
        );
    }
}
