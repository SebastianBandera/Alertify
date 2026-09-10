package app.alertify.procedures.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidTotpQrException;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.api.TotpQrAnalysisResult;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.procedures.templates.totp.TotpBase32;
import app.alertify.secret.api.SecretCreateRequest;
import app.alertify.secret.api.SecretResponse;
import app.alertify.services.secret.ApplicationSecretService;

/**
 * Decodes a pasted TOTP QR code image (an {@code otpauth://totp/...} URI),
 * creates the backing secret immediately, and returns the values needed to
 * prefill the "New procedure" form for the TOTP template. Creating the
 * secret before the procedure is saved is intentional: if the user cancels
 * the wizard afterwards, the secret is left behind as an accepted
 * side effect rather than rolled back.
 */
@Service
public class TotpQrAnalysisService {

    private static final String TOTP_TEMPLATE_KEY = "app.alertify.procedures.templates.TotpProcedureTemplate";
    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("SHA1", "SHA256", "SHA512");
    private static final Set<Integer> SUPPORTED_DIGITS = Set.of(6, 8);
    private static final int SECRET_NAME_MAX_LENGTH = 200;
    private static final DateTimeFormatter NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final ApplicationSecretService secretService;
    private final ProcedureTemplateParameterDefinitionRepository parameterRepository;

    public TotpQrAnalysisService(ApplicationSecretService secretService, ProcedureTemplateParameterDefinitionRepository parameterRepository) {
        this.secretService = secretService;
        this.parameterRepository = parameterRepository;
    }

    public TotpQrAnalysisResult analyze(MultipartFile file) {
        ParsedTotpUri parsed = parseOtpAuthUri(decodeQrText(file));
        TotpBase32.decode(parsed.secret()); // fail fast if the QR secret is not valid Base32

        String algorithm = parsed.algorithm() != null ? parsed.algorithm() : defaultParameterValue("algorithm");
        int digits = parsed.digits() != null ? parsed.digits() : Integer.parseInt(defaultParameterValue("digits"));
        int periodSeconds = parsed.periodSeconds() != null ? parsed.periodSeconds() : Integer.parseInt(defaultParameterValue("periodSeconds"));

        SecretResponse secret = createSecretWithRetry(parsed.issuer(), parsed.account(), parsed.secret());
        String suggestedProcedureName = buildSuggestedProcedureName(parsed.issuer(), parsed.account());

        return new TotpQrAnalysisResult(secret.id(), secret.name(), algorithm, digits, periodSeconds, suggestedProcedureName);
    }

    private String decodeQrText(MultipartFile file) {
        BufferedImage image;
        try (InputStream input = file.getInputStream()) {
            image = ImageIO.read(input);
        } catch (IOException exception) {
            throw new InvalidTotpQrException("The uploaded file could not be read", exception);
        }
        if (image == null)
            throw new InvalidTotpQrException("The uploaded file is not a readable image");

        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Result result = new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
            return result.getText();
        } catch (NotFoundException | ChecksumException | FormatException exception) {
            throw new InvalidTotpQrException("No QR code was found in the image", exception);
        }
    }

    private ParsedTotpUri parseOtpAuthUri(String text) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException exception) {
            throw new InvalidTotpQrException("The QR code does not contain a valid otpauth URI", exception);
        }
        if (!"otpauth".equalsIgnoreCase(uri.getScheme()))
            throw new InvalidTotpQrException("The QR code is not an otpauth code");
        if ("hotp".equalsIgnoreCase(uri.getHost()))
            throw new InvalidTotpQrException("HOTP codes are not supported, only TOTP");
        if (!"totp".equalsIgnoreCase(uri.getHost()))
            throw new InvalidTotpQrException("The QR code is not a TOTP code");

        String label = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        String labelIssuer = null;
        String account = label.isBlank() ? null : label;
        int separator = label.indexOf(':');
        if (separator >= 0) {
            labelIssuer = label.substring(0, separator).trim();
            account = label.substring(separator + 1).trim();
        }

        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String secret = query.getFirst("secret");
        if (secret == null || secret.isBlank())
            throw new InvalidTotpQrException("The QR code does not contain a TOTP secret");

        String issuer = firstNonBlank(query.getFirst("issuer"), labelIssuer);
        return new ParsedTotpUri(issuer, account, secret,
                normalizeAlgorithm(query.getFirst("algorithm")),
                normalizeDigits(query.getFirst("digits")),
                normalizePeriod(query.getFirst("period")));
    }

    private String normalizeAlgorithm(String value) {
        if (value == null || value.isBlank())
            return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ALGORITHMS.contains(normalized))
            throw new InvalidTotpQrException("The QR code specifies an unsupported algorithm: " + value);
        return normalized;
    }

    private Integer normalizeDigits(String value) {
        if (value == null || value.isBlank())
            return null;
        int digits = parsePositiveInt(value, "digits");
        if (!SUPPORTED_DIGITS.contains(digits))
            throw new InvalidTotpQrException("The QR code specifies unsupported digits: " + value);
        return digits;
    }

    private Integer normalizePeriod(String value) {
        return value == null || value.isBlank() ? null : parsePositiveInt(value, "period");
    }

    private int parsePositiveInt(String value, String fieldName) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new InvalidTotpQrException("The QR code specifies an invalid " + fieldName + ": " + value);
        }
        if (parsed <= 0)
            throw new InvalidTotpQrException("The QR code specifies a non-positive " + fieldName + ": " + value);
        return parsed;
    }

    private String defaultParameterValue(String parameterKey) {
        List<ProcedureTemplateParameterDefinition> parameters =
                parameterRepository.findAllByTemplate_TemplateKey(TOTP_TEMPLATE_KEY);
        return parameters.stream()
                .filter(parameter -> parameter.getParameterKey().equals(parameterKey))
                .findFirst()
                .map(ProcedureTemplateParameterDefinition::getDefaultValue)
                .orElseThrow(() -> new IllegalStateException(
                        "TOTP template parameter '" + parameterKey + "' has no registered default"));
    }

    private SecretResponse createSecretWithRetry(String issuer, String account, String rawSecret) {
        try {
            return createSecret(issuer, account, rawSecret);
        } catch (ConflictException firstConflict) {
            try {
                return createSecret(issuer, account, rawSecret);
            } catch (ConflictException secondConflict) {
                throw new InvalidTotpQrException(
                        "Could not create a unique secret name for this QR code, please try again", secondConflict);
            }
        }
    }

    private SecretResponse createSecret(String issuer, String account, String rawSecret) {
        String name = buildSecretName(issuer, account);
        SecretCreateRequest request = new SecretCreateRequest(
                name, "Created by the TOTP QR wizard", rawSecret, Set.of(), false);
        return secretService.create(request);
    }

    private String buildSecretName(String issuer, String account) {
        String identity = identity(issuer, account);
        String timestamp = NAME_TIMESTAMP.format(Instant.now());
        String name = identity == null ? "TOTP %s".formatted(timestamp) : "%s — TOTP %s".formatted(identity, timestamp);
        return truncate(name);
    }

    private String buildSuggestedProcedureName(String issuer, String account) {
        String identity = identity(issuer, account);
        return identity == null ? "Código TOTP" : "Código TOTP – %s".formatted(identity);
    }

    private static String identity(String issuer, String account) {
        boolean hasIssuer = issuer != null && !issuer.isBlank();
        boolean hasAccount = account != null && !account.isBlank();
        if (hasIssuer && hasAccount)
            return "%s (%s)".formatted(issuer, account);
        if (hasAccount)
            return account;
        return hasIssuer ? issuer : null;
    }

    private static String truncate(String value) {
        return value.length() > SECRET_NAME_MAX_LENGTH ? value.substring(0, SECRET_NAME_MAX_LENGTH) : value;
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank())
            return primary;
        return secondary != null && !secondary.isBlank() ? secondary : null;
    }

    private record ParsedTotpUri(String issuer, String account, String secret, String algorithm, Integer digits, Integer periodSeconds) {
    }
}
