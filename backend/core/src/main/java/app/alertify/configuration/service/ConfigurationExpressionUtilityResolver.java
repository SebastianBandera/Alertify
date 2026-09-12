package app.alertify.configuration.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationExpressionException;

/**
 * Resolves built-in {@code utils.NAME} expression values from one timestamp
 * snapshot so every utility referenced by the same evaluation is consistent,
 * and applies {@code utils.FUNCTION(argument)} transformations such as the
 * base64 family to an already evaluated argument.
 */
@Component
public class ConfigurationExpressionUtilityResolver {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final List<String> NAMES = List.of(
            "YEAR",
            "MONTH",
            "MONTH_PADDED",
            "DAY",
            "DAY_PADDED",
            "HOUR",
            "HOUR_PADDED",
            "MINUTE",
            "MINUTE_PADDED",
            "SECOND",
            "SECOND_PADDED",
            "DATE",
            "TIME",
            "DATE_TIME",
            "OFFSET_DATE_TIME",
            "DAY_OF_WEEK",
            "DAY_OF_YEAR",
            "WEEK_OF_YEAR",
            "WEEK_YEAR",
            "EPOCH_SECONDS",
            "EPOCH_MILLIS",
            "TIME_ZONE",
            "UTC_OFFSET"
    );

    private static final List<String> FUNCTIONS = List.of(
            "BASE64",
            "BASE64_NOPAD",
            "BASE64_URL",
            "BASE64_URL_NOPAD",
            "BASE64_MIME",
            "BASE64_DECODE",
            "BASE64_URL_DECODE",
            "BASE64_MIME_DECODE"
    );

    public List<String> names() {
        return NAMES;
    }

    public List<String> functionNames() {
        return FUNCTIONS;
    }

    public ZonedDateTime snapshot() {
        return ZonedDateTime.now();
    }

    public void ensureSupported(String name) {
        ensureSupported(name, false);
    }

    public void ensureSupported(String name, boolean function) {
        if (function && !FUNCTIONS.contains(name)) {
            throw new InvalidConfigurationExpressionException(
                    "Unsupported configuration expression utility function '" + name + "(...)'"
            );
        }
        if (!function && !NAMES.contains(name)) {
            String hint = FUNCTIONS.contains(name) ? "; it requires an argument, use utils." + name + "(...)" : "";
            throw new InvalidConfigurationExpressionException(
                    "Unsupported configuration expression utility '" + name + "'" + hint
            );
        }
    }

    public String apply(String name, String argument) {
        ensureSupported(name, true);
        byte[] input = argument.getBytes(StandardCharsets.UTF_8);
        try {
            return switch (name) {
                case "BASE64" -> Base64.getEncoder().encodeToString(input);
                case "BASE64_NOPAD" -> Base64.getEncoder().withoutPadding().encodeToString(input);
                case "BASE64_URL" -> Base64.getUrlEncoder().encodeToString(input);
                case "BASE64_URL_NOPAD" -> Base64.getUrlEncoder().withoutPadding().encodeToString(input);
                case "BASE64_MIME" -> Base64.getMimeEncoder().encodeToString(input);
                case "BASE64_DECODE" -> utf8(Base64.getDecoder().decode(argument.trim()), name);
                case "BASE64_URL_DECODE" -> utf8(Base64.getUrlDecoder().decode(argument.trim()), name);
                case "BASE64_MIME_DECODE" -> utf8(Base64.getMimeDecoder().decode(argument), name);
                default -> throw new IllegalStateException("Unexpected utility function: " + name);
            };
        } catch (IllegalArgumentException exception) {
            throw new InvalidConfigurationExpressionException(
                    "utils." + name + " received an argument that is not valid base64", exception
            );
        }
    }

    private static String utf8(byte[] decoded, String name) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidConfigurationExpressionException(
                    "utils." + name + " decoded bytes that are not valid UTF-8 text", exception
            );
        }
    }

    public String resolve(String name, ZonedDateTime now) {
        ensureSupported(name);
        return switch (name) {
            case "YEAR" -> Integer.toString(now.getYear());
            case "MONTH" -> Integer.toString(now.getMonthValue());
            case "MONTH_PADDED" -> twoDigits(now.getMonthValue());
            case "DAY" -> Integer.toString(now.getDayOfMonth());
            case "DAY_PADDED" -> twoDigits(now.getDayOfMonth());
            case "HOUR" -> Integer.toString(now.getHour());
            case "HOUR_PADDED" -> twoDigits(now.getHour());
            case "MINUTE" -> Integer.toString(now.getMinute());
            case "MINUTE_PADDED" -> twoDigits(now.getMinute());
            case "SECOND" -> Integer.toString(now.getSecond());
            case "SECOND_PADDED" -> twoDigits(now.getSecond());
            case "DATE" -> now.toLocalDate().toString();
            case "TIME" -> TIME_FORMATTER.format(now);
            case "DATE_TIME" -> DATE_TIME_FORMATTER.format(now);
            case "OFFSET_DATE_TIME" -> DATE_TIME_FORMATTER.format(now) + now.getOffset().getId();
            case "DAY_OF_WEEK" -> Integer.toString(now.getDayOfWeek().getValue());
            case "DAY_OF_YEAR" -> Integer.toString(now.getDayOfYear());
            case "WEEK_OF_YEAR" -> Integer.toString(now.get(WeekFields.ISO.weekOfWeekBasedYear()));
            case "WEEK_YEAR" -> Integer.toString(now.get(WeekFields.ISO.weekBasedYear()));
            case "EPOCH_SECONDS" -> Long.toString(now.toEpochSecond());
            case "EPOCH_MILLIS" -> Long.toString(now.toInstant().toEpochMilli());
            case "TIME_ZONE" -> now.getZone().getId();
            case "UTC_OFFSET" -> now.getOffset().getId();
            default -> throw new IllegalStateException("Unexpected utility: " + name.toUpperCase(Locale.ROOT));
        };
    }

    private static String twoDigits(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }
}
