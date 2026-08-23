package app.alertify.logging;

import java.util.Set;

/**
 * Maps HTTP outcomes to application log severity, treating selected expected
 * business conflicts as informational instead of operational warnings.
 */
public final class ApiResponseLogLevelResolver {

    private static final Set<String> EXPECTED_BUSINESS_ERROR_CODES = Set.of(
            "CONFIGURATION_TAG_IN_USE", "SECRET_TAG_IN_USE"
    );

    private ApiResponseLogLevelResolver() {
    }

    public static ApplicationLogLevel resolve(int status, String errorCode) {
        if (status >= 500) {
            return ApplicationLogLevel.ERROR;
        }
        if (isExpectedBusinessResponse(status, errorCode)) {
            return ApplicationLogLevel.INFO;
        }
        if (status >= 400) {
            return ApplicationLogLevel.WARN;
        }
        return ApplicationLogLevel.INFO;
    }

    static boolean isExpectedBusinessResponse(int status, String errorCode) {
        return status >= 400
                && status < 500
                && errorCode != null
                && EXPECTED_BUSINESS_ERROR_CODES.contains(errorCode);
    }
}
