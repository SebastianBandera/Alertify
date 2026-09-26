const BYTE_ARRAY_JAVA_TYPE = '[B';
const ARTIFACT_INPUT_JAVA_TYPE = 'app.alertify.procedures.artifact.ProcedureArtifactInput';
const DATABASE_CREDENTIALS_JAVA_TYPE = 'app.alertify.worker.contract.DatabaseCredentials';
const GIT_CREDENTIALS_JAVA_TYPE = 'app.alertify.worker.contract.GitCredentials';
const OIDC_TOKEN_SET_JAVA_TYPE = 'app.alertify.worker.contract.OidcTokenSet';
const KUBECONFIG_CREDENTIALS_JAVA_TYPE = 'app.alertify.worker.contract.KubeconfigCredentials';

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
  const structuredType = javaType === DATABASE_CREDENTIALS_JAVA_TYPE ? 'DB_SECRET'
    : javaType === GIT_CREDENTIALS_JAVA_TYPE ? 'GIT_SECRET'
    : javaType === OIDC_TOKEN_SET_JAVA_TYPE ? 'OIDC_TOKEN_SET'
    : javaType === KUBECONFIG_CREDENTIALS_JAVA_TYPE ? 'KUBECONFIG'
    : null;
  if (binaryRequired !== (valueType === 'BINARY')) return false;
  if (structuredType !== null && valueType !== structuredType) return false;
  if (structuredType === null && ['DB_SECRET', 'GIT_SECRET', 'OIDC_TOKEN_SET', 'KUBECONFIG'].includes(valueType ?? '')) return false;
  if (allowedSecretValueTypes.length === 0) return true;
  return valueType !== null && allowedSecretValueTypes.includes(valueType);
}
