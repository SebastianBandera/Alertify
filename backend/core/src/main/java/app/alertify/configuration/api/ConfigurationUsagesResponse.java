package app.alertify.configuration.api;

import java.util.List;

/** Resources that use a configuration, including expression dependencies. */
public record ConfigurationUsagesResponse(int totalCount, List<Usage> direct, List<Usage> indirect) {

    public record Usage(String type, Long id, String name, Boolean enabled) {
    }
}
