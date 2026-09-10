/** Short Java class name from a fully-qualified template key, e.g. "app.alertify.alerts.templates.InternetConnectionAlertTemplate" -> "InternetConnectionAlertTemplate". */
export function templateClassName(templateKey: string): string {
  const separator = templateKey.lastIndexOf('.');
  return separator < 0 ? templateKey : templateKey.substring(separator + 1);
}
