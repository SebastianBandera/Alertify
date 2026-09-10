import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { ApiRequestError, PageResponse } from './configuration-api.service';

export interface SystemConfiguration {
  readonly id: number;
  readonly version: number;
  readonly name: string;
  readonly value: unknown | null;
  readonly valueHidden: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SystemConfigurationUpdateRequest {
  readonly version: number;
  readonly value: unknown;
}

interface ApiErrorResponse {
  readonly code?: string;
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class SystemConfigurationApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async listSystemConfigurations(
    pageNumber: number,
    pageSize: number,
  ): Promise<PageResponse<SystemConfiguration>> {
    const params = new URLSearchParams({
      page: String(pageNumber),
      size: String(pageSize),
      sort: 'name,asc',
    });
    return this.request<PageResponse<SystemConfiguration>>(
      `/api/system-configurations?${params.toString()}`,
    );
  }

  async updateSystemConfiguration(
    id: number,
    request: SystemConfigurationUpdateRequest,
  ): Promise<SystemConfiguration> {
    return this.request(`/api/system-configurations/${id}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    });
  }

  /**
   * Regenerates the value entirely server-side (a fresh random value is
   * generated and saved in the same request) - no value ever needs to be
   * sent from the browser for this case.
   */
  async regenerateSystemConfiguration(id: number, version: number): Promise<SystemConfiguration> {
    return this.request(`/api/system-configurations/${id}/regenerate`, {
      method: 'POST',
      body: JSON.stringify({ version }),
    });
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await this.authService.getAccessToken();
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    if (init.body) headers.set('Content-Type', 'application/json');

    const response = await fetch(`${this.apiBaseUrl}${path}`, {
      ...init,
      headers,
    });

    if (!response.ok) throw await this.responseError(response);

    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  private async responseError(response: Response): Promise<Error> {
    let message = `Request failed with status ${response.status}.`;
    try {
      const error = (await response.json()) as ApiErrorResponse;
      const fieldMessage = error.fieldErrors ? Object.values(error.fieldErrors)[0] : undefined;
      message = fieldMessage ?? error.message ?? message;
      return new ApiRequestError(message, error.code);
    } catch {
      // Keep the HTTP status message when the response is not JSON.
    }
    return new Error(message);
  }
}
