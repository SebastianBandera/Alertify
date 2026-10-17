package app.alertify.ai.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiMemoryRepository extends JpaRepository<AiMemory, Long> {
    Page<ManagementView> findAllByOrderByUpdatedAtDescIdDesc(Pageable page);

    @Modifying
    @Query("delete from AiMemory")
    int deleteAllMemories();

    Optional<AiMemory> findByUserSubjectAndMemoryKey(String userSubject, String memoryKey);

    List<IndexView> findByUserSubjectOrderByMemoryKeyAsc(String userSubject, Pageable page);

    @Query("""
            select m from AiMemory m where m.userSubject = :subject and m.memoryKey > :afterKey
              and (:query = '' or locate(:query, lower(m.memoryKey)) > 0 or locate(:query, lower(m.content)) > 0)
            order by m.memoryKey asc
            """)
    List<AiMemory> search(@Param("subject") String subject, @Param("query") String query, @Param("afterKey") String afterKey, Pageable page);

    interface IndexView {
        String getMemoryKey();
        Instant getUpdatedAt();
    }

    interface ManagementView {
        Long getId();
        String getUserSubject();
        String getMemoryKey();
        long getVersion();
        Instant getCreatedAt();
        Instant getUpdatedAt();
    }
}
