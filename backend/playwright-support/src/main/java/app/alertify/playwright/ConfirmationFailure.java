package app.alertify.playwright;

/** Fixed diagnostics only: never retain a dialog message or Playwright cause. */
final class ConfirmationFailure extends IllegalArgumentException {
    final String code;

    ConfirmationFailure(String code) {
        super(code.equals("CONFIRM_MISMATCH") ? "The dialog did not match the single authorized confirmation" : "The expected confirmation was not shown before the action timeout");
        this.code = code;
    }
}
