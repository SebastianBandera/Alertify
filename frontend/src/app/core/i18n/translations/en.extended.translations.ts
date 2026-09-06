/**
 * Add project-specific translations here, such as localization keys declared
 * by custom AlertTemplate implementations. This file is merged after the core
 * English dictionary so a fork can extend localization without editing it.
 */
export const EN_EXTENDED_TRANSLATIONS: Readonly<Record<string, string>> = {
  'alerts.template.totpProcedureExample.name': 'Invoke TOTP procedure',
  'alerts.template.totpProcedureExample.description': 'Invokes a configured TOTP procedure synchronously and waits for its result.',
  'alerts.template.totpProcedureExample.procedure': 'TOTP procedure',
  'alerts.template.totpProcedureExample.procedureDescription': 'Procedure instance to execute while this alert is running.',
};
