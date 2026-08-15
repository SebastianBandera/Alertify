import Keycloak, { KeycloakProfile } from 'keycloak-js';

import { KeycloakRuntimeConfig } from '../config/runtime-config';

export class AuthService {
  private readonly keycloak: Keycloak;
  private profile: KeycloakProfile | undefined;

  constructor(config: KeycloakRuntimeConfig) {
    this.keycloak = new Keycloak(config);
  }

  async initialize(): Promise<void> {
    const authenticated = await this.keycloak.init({
      onLoad: 'login-required',
      flow: 'standard',
      pkceMethod: 'S256',
      checkLoginIframe: true,
    });

    if (!authenticated) {
      await this.keycloak.login();
      return;
    }

    this.profile = await this.keycloak.loadUserProfile();
  }

  get displayName(): string {
    const fullName = [this.profile?.firstName, this.profile?.lastName]
      .filter((part): part is string => Boolean(part))
      .join(' ')
      .trim();

    return fullName || this.profile?.username || 'Signed-in user';
  }

  get userInitial(): string {
    return this.displayName.charAt(0).toUpperCase();
  }

  logout(): Promise<void> {
    return this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
