package app.alertify.secret.api;

import java.util.List;

/** Resources that use a secret, including expression dependencies. */
public record SecretUsagesResponse(int totalCount, List<Usage> direct, List<Usage> indirect) {

    public record Usage(String type, Long id, String name, Boolean enabled) {
    }
}
