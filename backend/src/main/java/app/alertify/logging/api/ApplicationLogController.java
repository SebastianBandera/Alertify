package app.alertify.logging.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.logging.ApplicationEventLogger;
import app.alertify.logging.ApplicationLogService;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/logs")
public class ApplicationLogController {

    private final ApplicationLogService service;
    private final ApplicationEventLogger eventLogger;

    public ApplicationLogController(
            ApplicationLogService service,
            ApplicationEventLogger eventLogger) {
        this.service = service;
        this.eventLogger = eventLogger;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<ApplicationLogResponse> search(
            @RequestParam MultiValueMap<String, String> params,
            @PageableDefault(size = 25, sort = "eventAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.search(params, pageable);
    }

    @PostMapping("/login")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> login(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clientAddress", clientAddress(request));
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && !userAgent.isBlank()) data.put("userAgent", userAgent);
        eventLogger.success("USER_LOGIN", data);
        return ResponseEntity.noContent().build();
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",", 2)[0].trim();
    }
}
