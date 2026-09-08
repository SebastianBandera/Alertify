package app.alertify.secretsexport;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import app.alertify.jpa.audit.AuditRevisionEntity;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.services.secret.ApplicationSecretService;
import app.alertify.services.secret.SecretAccessService;
import app.alertify.services.secret.SecretTagService;
import app.alertify.services.secret.SymmetricKeyService;
import app.alertify.services.secret.WritableSecretService;

/**
 * Deliberately narrow bootstrap for the secrets export/import CLI
 * ({@link SecretExportImportCli}). It wires only the JPA entities,
 * repositories and encryption services the tool needs, instead of reusing
 * {@code AlertifyApplication}'s component scan, so that running it as a
 * separate process never triggers the main application's hook reconciliation,
 * gRPC discovery or scheduling side effects. {@code ApplicationConfiguration}
 * is scanned only because {@code DatabaseKeyPartSource} reads the symmetric
 * key part from it, not because configuration values are exported.
 * {@code AuditRevisionEntity} must be included too: all three audited
 * entities need Envers to resolve the custom revision entity in
 * {@code app.alertify.jpa.audit}, or it falls back to Envers' own default
 * revision entity, whose mapping does not match the {@code audit.revinfo}
 * schema created for the custom one.
 *
 * <p>This class lives under {@code app.alertify}, so {@code AlertifyApplication}'s
 * own component scan would otherwise pick it up too and merge its
 * {@code @EntityScan}/{@code @EnableJpaRepositories} into the main
 * application's persistence unit, corrupting Envers' revision metadata.
 * {@link #PROFILE} keeps it inert everywhere except the CLI's own context,
 * which is the only place that activates it.
 *
 * <p>{@code app.alertify.services.secret} also holds
 * {@code ApplicationSecretService}, {@code WritableSecretService},
 * {@code SecretTagService} and {@code SecretAccessService}, which all need
 * {@code ApplicationEventLogger} (and the audit-log subsystem behind it).
 * They are excluded from the scan rather than wired in, since pulling in
 * that subsystem would defeat the point of a minimal, isolated context.
 *
 * <p>{@code basePackageClasses} on {@code @EnableJpaRepositories} scans the
 * whole package of the given class, not just that interface: every JPA
 * repository in this codebase lives in the single flat package
 * {@code app.alertify.jpa.repository}, so an {@code includeFilters} is
 * required to keep repositories for entities outside this context's
 * persistence unit (e.g. {@code AlertTemplateDefinitionRepository}) from
 * being instantiated too.
 *
 * <p>The component scan also includes this class's own package
 * ({@code app.alertify.secretsexport}), which is what registers
 * {@link SecretExportImportService} as a bean — passing this configuration
 * class to {@code SpringApplicationBuilder} only bootstraps itself, it does
 * not implicitly scan its own package for other components.
 */
@Configuration
@Profile(SecretExportImportConfiguration.PROFILE)
@EnableAutoConfiguration
@EntityScan(basePackageClasses = { ApplicationSecret.class, Tag.class, ApplicationConfiguration.class, AuditRevisionEntity.class })
@EnableJpaRepositories(
        basePackageClasses = ApplicationSecretRepository.class,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = { ApplicationSecretRepository.class, TagRepository.class, ApplicationConfigurationRepository.class }
        )
)
@ComponentScan(basePackageClasses = { SymmetricKeyService.class, SecretExportImportConfiguration.class }, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = { ApplicationSecretService.class, WritableSecretService.class, SecretTagService.class, SecretAccessService.class }
))
class SecretExportImportConfiguration {

    static final String PROFILE = "secrets-tool";
}
