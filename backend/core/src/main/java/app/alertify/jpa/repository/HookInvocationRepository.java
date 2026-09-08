package app.alertify.jpa.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.hooks.model.HookInvocation;
import app.alertify.hooks.model.HookInvocationStatus;

public interface HookInvocationRepository extends JpaRepository<HookInvocation, Long> {
    @EntityGraph(attributePaths = "targets")
    Optional<HookInvocation> findByInvocationId(UUID invocationId);

    @EntityGraph(attributePaths = "targets")
    Optional<HookInvocation> findByHookPublicIdAndInvocationId(UUID hookPublicId, UUID invocationId);

    Page<HookInvocation> findAllByHook_Id(Long hookId, Pageable pageable);
    @EntityGraph(attributePaths = "targets")
    List<HookInvocation> findAllByStatus(HookInvocationStatus status);
    long countByHook_Id(Long hookId);
}
