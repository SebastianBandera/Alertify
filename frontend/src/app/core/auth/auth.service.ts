import Keycloak, { KeycloakProfile } from 'keycloak-js';

import { KeycloakRuntimeConfig } from '../config/runtime-config';

export class AuthService {
  private readonly keycloak: Keycloak;
  private readonly rolesClientId: string;
  private profile: KeycloakProfile | undefined;

  constructor(config: KeycloakRuntimeConfig) {
    this.rolesClientId = config.rolesClientId;
    this.keycloak = new Keycloak({
      url: config.url,
      realm: config.realm,
      clientId: config.clientId,
    });
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

  get isAdmin(): boolean {
    return this.keycloak.hasResourceRole('ADMIN', this.rolesClientId);
  }
  get sessionIdentifier(): string {
    const token = this.keycloak.tokenParsed;
    const identifier = token?.['sid'] ?? token?.['session_state'];
    if (typeof identifier === 'string' && identifier) return identifier;

    return `${String(token?.['sub'] ?? 'unknown')}:${String(token?.['iat'] ?? 'unknown')}`;
  }

  async getAccessToken(): Promise<string> {
    await this.keycloak.updateToken(30);
    if (!this.keycloak.token) {
      throw new Error('The authenticated access token is unavailable.');
    }
    return this.keycloak.token;
  }

  logout(): Promise<void> {
    return this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
