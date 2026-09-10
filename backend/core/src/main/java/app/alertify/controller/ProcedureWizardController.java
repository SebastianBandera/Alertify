package app.alertify.controller;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.procedures.api.TotpQrAnalysisResult;
import app.alertify.procedures.service.TotpQrAnalysisService;

/**
 * Alternative, guided ways to create a procedure instance, distinct from the
 * generic "New procedure" form. Each wizard gets its own endpoint under this
 * prefix.
 */
@RestController
@RequestMapping("/api/procedures/wizards")
@PreAuthorize("hasRole('ADMIN')")
public class ProcedureWizardController {

    private final TotpQrAnalysisService totpQrAnalysisService;

    public ProcedureWizardController(TotpQrAnalysisService totpQrAnalysisService) {
        this.totpQrAnalysisService = totpQrAnalysisService;
    }

    @PostMapping(value = "/totp/qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TotpQrAnalysisResult analyzeTotpQr(@RequestParam("file") MultipartFile file) {
        return totpQrAnalysisService.analyze(file);
    }
}
