package app.alertify.alerts.api;

import java.util.List;

public record AlertBindingOptionsResponse(
    List<AlertBindingOptionResponse> configurations,
    List<AlertBindingOptionResponse> secrets
) {
}
