import { AlertMessageFormatters } from './alert-message-formatter';

/**
 * Add project-specific tile message formatters here, such as the ones for
 * custom AlertTemplate implementations. Key each formatter by the template's
 * fully qualified class name; these entries are merged after the core ones,
 * so a fork can also replace a core formatter without editing it. Put the
 * texts they translate in the *.extended.translations.ts dictionaries.
 *
 * Example:
 *   'app.alertify.alerts.templates.custom.QueueDepthAlertTemplate': (context) =>
 *     context.translate('alertMessage.queueDepth', { depth: context.formatNumber(context.message['depth']) }),
 */
export const EXTENDED_ALERT_MESSAGE_FORMATTERS: AlertMessageFormatters = {};
