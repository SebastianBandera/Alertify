package app.alertify.controller;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import app.alertify.api.error.InvalidHookRequestException;
import app.alertify.hooks.api.HookAcceptedResponse;
import app.alertify.hooks.api.HookInvocationResponse;
import app.alertify.hooks.service.HookInvocationService;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/hooks/{publicId}")
public class HookInvocationController {

    public static final String TOKEN_HEADER = "X-Alertify-Hook-Token";
    private final HookInvocationService service;

    public HookInvocationController(HookInvocationService service) { this.service = service; }

    @PostMapping("/invoke")
    public ResponseEntity<HookAcceptedResponse> invoke(@PathVariable UUID publicId, @RequestHeader(value = TOKEN_HEADER, required = false) String token, HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1)
            throw new InvalidHookRequestException("Hook invocation does not accept a request body");

        HookAcceptedResponse accepted = service.invoke(publicId, token);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/hooks/{publicId}/invocations/{invocationId}")
                .buildAndExpand(publicId, accepted.invocationId()).toUri();
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, location.toString()).body(accepted);
    }

    @GetMapping("/invocations/{invocationId}")
    public HookInvocationResponse status(@PathVariable UUID publicId, @PathVariable UUID invocationId, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return service.status(publicId, invocationId, token);
    }
}
