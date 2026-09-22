const BYTE_ARRAY_JAVA_TYPE = '[B';
const ARTIFACT_INPUT_JAVA_TYPE = 'app.alertify.procedures.artifact.ProcedureArtifactInput';
const DATABASE_CREDENTIALS_JAVA_TYPE = 'app.alertify.worker.contract.DatabaseCredentials';

/**
 * Mirrors the backend's ParameterValueTypeCompatibility: a parameter's java
 * type may physically require a specific configuration/secret value type
 * (BINARY, DB_SECRET), and a template may further narrow the choice with an
 * optional, explicit allow-list. An empty allow-list means no additional
 * restriction beyond the physical requirement.
 */
export function isCompatibleConfigurationValueType(
  javaType: string,
  allowedConfigurationValueTypes: readonly string[],
  valueType: string | null
): boolean {
  const binaryRequired = javaType === BYTE_ARRAY_JAVA_TYPE || javaType === ARTIFACT_INPUT_JAVA_TYPE;
  if (binaryRequired !== (valueType === 'BINARY')) return false;
  if (allowedConfigurationValueTypes.length === 0) return true;
  return valueType !== null && allowedConfigurationValueTypes.includes(valueType);
}

export function isCompatibleSecretValueType(
  javaType: string,
  allowedSecretValueTypes: readonly string[],
  valueType: string | null
): boolean {
  const binaryRequired = javaType === BYTE_ARRAY_JAVA_TYPE || javaType === ARTIFACT_INPUT_JAVA_TYPE;
  const dbSecretRequired = javaType === DATABASE_CREDENTIALS_JAVA_TYPE;
  if (binaryRequired !== (valueType === 'BINARY')) return false;
  if (dbSecretRequired !== (valueType === 'DB_SECRET')) return false;
  if (allowedSecretValueTypes.length === 0) return true;
  return valueType !== null && allowedSecretValueTypes.includes(valueType);
}
