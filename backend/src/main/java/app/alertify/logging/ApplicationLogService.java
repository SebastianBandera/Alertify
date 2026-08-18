package app.alertify.logging;

import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import app.alertify.jpa.entity.ApplicationLog;
import app.alertify.jpa.repository.ApplicationLogRepository;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.api.ApplicationLogResponse;

@Service
public class ApplicationLogService {

    private static final Map<String, String> FILTER_ALIASES = Map.of(
        "user", "username",
        "subject", "userSubject",
        "date", "eventAt",
        "level", "level.code",
        "source", "source.code",
        "event", "event.code"
    );
    private static final Set<String> FILTER_FIELDS = Set.of(
        "id", "eventAt", "level.code", "source.code", "event.code", "outcome",
        "userSubject", "username", "requestId", "path"
    );
    private static final Set<String> SORT_FIELDS = Set.of(
        "id", "eventAt", "level", "source", "event", "outcome", "userSubject", "username",
        "path"
    );
    private static final Map<String, String> SORT_ALIASES = Map.of(
        "level", "level.code",
        "source", "source.code",
        "event", "event.code"
    );

    private final ApplicationLogRepository repository;

    public ApplicationLogService(ApplicationLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<ApplicationLogResponse> search(
            MultiValueMap<String, String> params, Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!SORT_FIELDS.contains(order.getProperty())) {
                throw new InvalidFilterException("sort");
            }
        });
        Specification<ApplicationLog> specification =
            DynamicSpecification.from(params, FILTER_ALIASES, FILTER_FIELDS);
        Pageable queryPageable = PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            mapSort(pageable.getSort())
        );
        return repository.findAll(specification, queryPageable)
            .map(ApplicationLogService::toResponse);
    }

    private static Sort mapSort(Sort sort) {
        return Sort.by(sort.stream()
            .map(order -> {
                Sort.Order mappedOrder = new Sort.Order(
                    order.getDirection(),
                    SORT_ALIASES.getOrDefault(order.getProperty(), order.getProperty())
                ).with(order.getNullHandling());
                return order.isIgnoreCase() ? mappedOrder.ignoreCase() : mappedOrder;
            })
            .toList());
    }

    private static ApplicationLogResponse toResponse(ApplicationLog log) {
        return new ApplicationLogResponse(
            log.getId(), log.getEventAt(), log.getLevel(), log.getSource(), log.getEvent(),
            log.getOutcome(), log.getUserSubject(), log.getUsername(), log.getRequestId(),
            log.getPath(), log.getData().deepCopy()
        );
    }
}
