/**
 * Add project-specific translations here, such as localization keys declared
 * by custom AlertTemplate implementations. This file is merged after the core
 * English dictionary so a fork can extend localization without editing it.
 */
export const EN_EXTENDED_TRANSLATIONS: Readonly<Record<string, string>> = {
  'alerts.template.resourceObserver.name': 'Resource result observer',
  'alerts.template.resourceObserver.description': 'Observes the latest completed Pipe, Procedure, or Hook result without invoking the resource. Reports WARN when no completed result exists.',
  'alerts.template.resourceObserver.kind': 'Resource kind',
  'alerts.template.resourceObserver.kindDescription': 'Choose Pipe, Procedure, or Hook to observe its latest terminal execution.',
  'alerts.template.resourceObserver.id': 'Observed resource',
  'alerts.template.resourceObserver.idDescription': 'Select an existing resource. Observed resources cannot be deleted while referenced by this alert.',
  'observer.resource.select': 'Select the observed resource',
  'observer.resource.empty': 'No resources of this kind are available.',
  'observer.resource.loading': 'Loading resources…',
  'observer.resource.error': 'Resources could not be loaded.',
  'observer.resource.disabled': 'Disabled; the last completed result can still be observed.',
};
