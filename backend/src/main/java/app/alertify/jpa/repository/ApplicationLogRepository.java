package app.alertify.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import app.alertify.jpa.entity.ApplicationLog;

public interface ApplicationLogRepository
        extends JpaRepository<ApplicationLog, Long>, JpaSpecificationExecutor<ApplicationLog> {

    @Override
    @EntityGraph(attributePaths = { "level", "source", "event" })
    Page<ApplicationLog> findAll(Specification<ApplicationLog> specification, Pageable pageable);
}
