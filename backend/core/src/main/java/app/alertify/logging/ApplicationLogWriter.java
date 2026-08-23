package app.alertify.logging;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.entity.ApplicationLog;
import app.alertify.jpa.repository.ApplicationLogRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists one structured application event in an independent transaction so
 * log writes do not join or alter the caller's business transaction.
 */
@Service
class ApplicationLogWriter {

    private final ApplicationLogRepository repository;
    private final ApplicationLogCatalog catalog;
    private final JsonMapper jsonMapper;

    ApplicationLogWriter(ApplicationLogRepository repository, ApplicationLogCatalog catalog, JsonMapper jsonMapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(ApplicationLogCommand command) {
        repository.saveAndFlush(
                new ApplicationLog(
                        command.eventAt(), catalog.level(command.level()), catalog.source(command.source()),
                        catalog.event(command.event()),
                        command.outcome(), command.actor().subject(), command.actor().username(),
                        command.requestId(), command.path(), jsonMapper.valueToTree(command.data())
                )
        );
    }
}
