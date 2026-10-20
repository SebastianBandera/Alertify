package app.alertify.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import app.alertify.logging.ApplicationEventLogger;

class AiMemoryManagementSecurityTest {
    @Test
    void onlyAdministratorsCanReadOrDeleteAcrossOwners() {
        var repository = mock(AiMemoryRepository.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Security.class, AiMemoryManagementService.class);
            context.registerBean(AiMemoryRepository.class, () -> repository);
            context.registerBean(ApplicationEventLogger.class, () -> mock(ApplicationEventLogger.class));
            context.refresh();
            var service = context.getBean(AiMemoryManagementService.class);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user", "unused", List.of(new SimpleGrantedAuthority("ROLE_DASHBOARD"))));
            assertThatThrownBy(() -> service.list(0, 20)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.get(1L)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.delete(1L, 0)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(service::deleteAll).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(repository);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            when(repository.findAllByOrderByUpdatedAtDescIdDesc(any())).thenReturn(Page.empty());
            assertThat(service.list(0, 20).totalElements()).isZero();
            verify(repository).findAllByOrderByUpdatedAtDescIdDesc(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }
}
