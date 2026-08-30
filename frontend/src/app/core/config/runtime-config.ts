import { InjectionToken } from '@angular/core';

export interface KeycloakRuntimeConfig {
  readonly url: string;
  readonly realm: string;
  readonly clientId: string;
  readonly rolesClientId: string;
}

export interface RuntimeConfig {
  readonly apiBaseUrl: string;
  readonly keycloak: KeycloakRuntimeConfig;
}

export const RUNTIME_CONFIG = new InjectionToken<RuntimeConfig>('RUNTIME_CONFIG');

function requiredString(value: unknown, property: string): string {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Runtime configuration property ${property} is required.`);
  }
  return value;
}

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  const response = await fetch(new URL('config/runtime-config.json', document.baseURI), { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`Runtime configuration request failed with status ${response.status}.`);
  }

  const payload = (await response.json()) as Partial<RuntimeConfig>;
  const keycloak = payload.keycloak as Partial<KeycloakRuntimeConfig> | undefined;

  return {
    apiBaseUrl: requiredString(payload.apiBaseUrl, 'apiBaseUrl').replace(/\/$/, ''),
    keycloak: {
      url: requiredString(keycloak?.url, 'keycloak.url'),
      realm: requiredString(keycloak?.realm, 'keycloak.realm'),
      clientId: requiredString(keycloak?.clientId, 'keycloak.clientId'),
      rolesClientId: requiredString(keycloak?.rolesClientId, 'keycloak.rolesClientId'),
    },
  };
}
