package app.alertify.procedures.api;

/** Result of decoding a TOTP otpauth QR code and creating its backing secret. */
public record TotpQrAnalysisResult(
    Long secretId,
    String secretName,
    String algorithm,
    int digits,
    int periodSeconds,
    String suggestedProcedureName
) {
}
