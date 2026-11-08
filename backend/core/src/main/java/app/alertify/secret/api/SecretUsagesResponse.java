package app.alertify.secret.api;

import java.util.List;
import app.alertify.playwright.PlaywrightUsageIssue;

/** Resources that use a secret, including expression dependencies. */
public record SecretUsagesResponse(int totalCount, List<Usage> direct, List<Usage> indirect, boolean analysisComplete, List<String> analysisWarnings, List<PlaywrightUsageIssue> analysisIssues) {

    public SecretUsagesResponse(int totalCount, List<Usage> direct, List<Usage> indirect) {
        this(totalCount, direct, indirect, true, List.of(), List.of());
    }

    public record Usage(String type, Long id, String name, Boolean enabled) {
    }
}
