package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import app.alertify.hooks.model.HookInvocationTarget;

public interface HookInvocationTargetRepository extends JpaRepository<HookInvocationTarget, Long> {
    @Query("select count(target) from HookInvocationTarget target where target.invocation.hook.id = :hookId")
    long countByHookId(Long hookId);
}
