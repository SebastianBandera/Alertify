package app.alertify.logging;

/**
 * Internal identity snapshot attached to a structured application event.
 */
record LogActor(
    String subject,
    String username
) {
}
