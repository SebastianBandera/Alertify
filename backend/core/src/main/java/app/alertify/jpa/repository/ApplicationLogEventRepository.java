package app.alertify.jpa.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import app.alertify.jpa.entity.ApplicationLogEvent;

/**
 * Resolves normalized application log events by their stable code.
 */
public interface ApplicationLogEventRepository extends JpaRepository<ApplicationLogEvent, Short> {

    Optional<ApplicationLogEvent> findByCode(String code);

    @Query("select event.code from ApplicationLogEvent event order by event.code")
    List<String> findAllCodesOrderByCode();
}
