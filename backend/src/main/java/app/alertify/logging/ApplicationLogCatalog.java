package app.alertify.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import app.alertify.jpa.entity.ApplicationLogEvent;
import app.alertify.jpa.entity.ApplicationLogLevelDefinition;
import app.alertify.jpa.entity.ApplicationLogSource;
import app.alertify.jpa.repository.ApplicationLogEventRepository;
import app.alertify.jpa.repository.ApplicationLogLevelDefinitionRepository;
import app.alertify.jpa.repository.ApplicationLogSourceRepository;

@Component
class ApplicationLogCatalog {

    private final ApplicationLogLevelDefinitionRepository levelRepository;
    private final ApplicationLogSourceRepository sourceRepository;
    private final ApplicationLogEventRepository eventRepository;
    private final ConcurrentMap<String, ApplicationLogLevelDefinition> levels =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ApplicationLogSource> sources =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ApplicationLogEvent> events =
        new ConcurrentHashMap<>();

    ApplicationLogCatalog(
            ApplicationLogLevelDefinitionRepository levelRepository,
            ApplicationLogSourceRepository sourceRepository,
            ApplicationLogEventRepository eventRepository) {
        this.levelRepository = levelRepository;
        this.sourceRepository = sourceRepository;
        this.eventRepository = eventRepository;
    }

    ApplicationLogLevelDefinition level(ApplicationLogLevel level) {
        String code = level.name();
        return levels.computeIfAbsent(
            code,
            key -> levelRepository.findByCode(key)
                .orElseThrow(() -> missing("level", key))
        );
    }

    ApplicationLogSource source(String code) {
        return sources.computeIfAbsent(
            code,
            key -> sourceRepository.findByCode(key)
                .orElseThrow(() -> missing("source", key))
        );
    }

    ApplicationLogEvent event(String code) {
        return events.computeIfAbsent(
            code,
            key -> eventRepository.findByCode(key)
                .orElseThrow(() -> missing("event", key))
        );
    }

    private static IllegalStateException missing(String catalog, String code) {
        return new IllegalStateException(
            "Unknown application log " + catalog + " code: " + code
        );
    }
}
