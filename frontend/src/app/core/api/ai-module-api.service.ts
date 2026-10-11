import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';

export interface AiSettings {
  readonly enabled: boolean;
  readonly provider: string;
  readonly model: string;
  readonly reasoningEffort: string;
  readonly agentInstructions: string;
  readonly refreshPolicy: string;
}

export interface AiModuleState {
  readonly workerAvailable: boolean;
  readonly workerErrorCode: string;
  readonly enabled: boolean;
  readonly authMode: string;
  readonly sessionStatus: string;
  readonly accountLabel: string;
  readonly expiresAt: string;
  readonly refreshExpiresAt: string;
  readonly lastRefreshedAt: string;
  readonly refreshPolicy: string;
  readonly loginPending: boolean;
  readonly pendingLoginMode: string;
  readonly extensionInstanceId: string;
  readonly extensionProtocolVersion: number;
  readonly settings: AiSettings;
}

export interface BrowserLogin {
  readonly mode: 'DIRECT' | 'EXTENSION';
  readonly authorizationUrl: string;
  readonly expiresAt: string;
  readonly callbackTicket: string;
  readonly callbackUri: string;
  readonly extensionInstanceId: string;
  readonly extensionProtocolVersion: number;
}

export interface DeviceLogin {
  readonly status: string;
  readonly verificationUri: string;
  readonly userCode: string;
  readonly expiresAt: string;
  readonly intervalSeconds: number;
  readonly error: string;
}

export interface RefreshResult {
  readonly successful: boolean;
  readonly previousExpiresAt: string;
  readonly expiresAt: string;
  readonly refreshExpiresAt: string;
  readonly lastRefreshedAt: string;
  readonly message: string;
}

export interface OperationResult {
  readonly successful: boolean;
  readonly message: string;
}

@Injectable({ providedIn: 'root' })
export class AiModuleApiService {
  private readonly auth = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  module(): Promise<AiModuleState> {
    return this.request('/api/ai/module');
  }

  update(settings: AiSettings): Promise<AiSettings> {
    return this.request('/api/ai/settings', 'PUT', settings);
  }

  beginBrowser(mode: 'DIRECT' | 'EXTENSION'): Promise<BrowserLogin> {
    return this.request('/api/ai/codex/login/browser', 'POST', { mode });
  }

  beginDevice(): Promise<DeviceLogin> {
    return this.request('/api/ai/codex/login/device', 'POST');
  }

  deviceState(): Promise<DeviceLogin> {
    return this.request('/api/ai/codex/login/device');
  }

  cancelLogin(): Promise<OperationResult> {
    return this.request('/api/ai/codex/login-attempt', 'DELETE');
  }

  refresh(): Promise<RefreshResult> {
    return this.request('/api/ai/codex/refresh', 'POST');
  }

  logout(): Promise<OperationResult> {
    return this.request('/api/ai/codex/session', 'DELETE');
  }

  test(): Promise<OperationResult> {
    return this.request('/api/ai/codex/test', 'POST');
  }

  async downloadExtension(browser: 'chrome' | 'firefox'): Promise<void> {
    const token = await this.auth.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/ai/codex/extensions/${browser}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null) as { message?: string } | null;
      throw new Error(error?.message ?? `Request failed with status ${response.status}.`);
    }
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const fileName = disposition.match(/filename="?([^";]+)"?/i)?.[1] ?? `alertify-codex-${browser}.zip`;
    const url = URL.createObjectURL(await response.blob());
    try {
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      link.click();
    } finally {
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    }
  }

  private async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const token = await this.auth.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null) as { message?: string } | null;
      throw new Error(error?.message ?? `Request failed with status ${response.status}.`);
    }
    return await response.json() as T;
  }
}
