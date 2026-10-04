/**
 * Agregá aquí las traducciones propias del proyecto, por ejemplo las claves
 * declaradas por AlertTemplate personalizados. Este archivo se combina después
 * del diccionario base para extender la localización sin modificar el core.
 */
export const ES_UY_EXTENDED_TRANSLATIONS: Readonly<Record<string, string>> = {
  'alerts.template.resourceObserver.name': 'Observador de resultado de recurso',
  'alerts.template.resourceObserver.description': 'Observa el último resultado completado de un Pipe, Procedure o Hook sin ejecutar el recurso. Devuelve WARN si todavía no hay resultados completados.',
  'alerts.template.resourceObserver.kind': 'Tipo de recurso',
  'alerts.template.resourceObserver.kindDescription': 'Elegí Pipe, Procedure o Hook para observar su última ejecución terminal.',
  'alerts.template.resourceObserver.id': 'Recurso observado',
  'alerts.template.resourceObserver.idDescription': 'Seleccioná un recurso existente. No se puede eliminar mientras esta alerta lo observe.',
  'observer.resource.select': 'Seleccioná el recurso observado',
  'observer.resource.empty': 'No hay recursos de este tipo disponibles.',
  'observer.resource.loading': 'Cargando recursos…',
  'observer.resource.error': 'No se pudieron cargar los recursos.',
  'observer.resource.disabled': 'Deshabilitado; se puede observar el último resultado completado.',
};
