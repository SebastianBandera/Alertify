package app.alertify.configuration.api;

import java.util.List;
import app.alertify.playwright.PlaywrightUsageIssue;

/** Resources that use a configuration, including expression dependencies. */
public record ConfigurationUsagesResponse(int totalCount, List<Usage> direct, List<Usage> indirect, boolean analysisComplete, List<String> analysisWarnings, List<PlaywrightUsageIssue> analysisIssues) {

    public ConfigurationUsagesResponse(int totalCount, List<Usage> direct, List<Usage> indirect) {
        this(totalCount, direct, indirect, true, List.of(), List.of());
    }

    public record Usage(String type, Long id, String name, Boolean enabled) {
    }
}
