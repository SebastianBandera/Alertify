package app.alertify.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.binary.BinaryLimitsResponse;
import app.alertify.binary.BinaryPayloadService;

@RestController
@RequestMapping("/api/binary-values")
@PreAuthorize("hasRole('ADMIN')")
public class BinaryLimitsController {
    private final BinaryPayloadService service;
    public BinaryLimitsController(BinaryPayloadService service) { this.service = service; }
    @GetMapping("/limits") public BinaryLimitsResponse limits() { return new BinaryLimitsResponse(service.maximumBytes()); }
}
