import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';

export type ConfigurationValueType =
  | 'STRING'
  | 'INTEGER'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'DATE'
  | 'DATE_TIME'
  | 'JSON';

export interface ConfigurationTag {
  readonly id: number;
  readonly version: number;
  readonly scope: 'CONFIGURATION';
  readonly name: string;
  readonly color: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ApplicationConfiguration {
  readonly id: number;
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly valueType: ConfigurationValueType;
  readonly value: unknown;
  readonly tags: readonly ConfigurationTag[];
  readonly systemManaged: boolean;
  readonly deletable: boolean;
  readonly changeWarning: 'SECRET_LOSS' | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ConfigurationWriteRequest {
  readonly version?: number;
  readonly name: string;
  readonly description: string | null;
  readonly valueType: ConfigurationValueType;
  readonly value: unknown;
  readonly tagIds: readonly number[];
}

export interface TagWriteRequest {
  readonly version?: number;
  readonly name: string;
  readonly color: string;
}

interface PageResponse<T> {
  readonly content: readonly T[];
}

interface ApiErrorResponse {
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class ConfigurationApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async listConfigurations(
    search: string,
    tagIds: readonly number[],
  ): Promise<readonly ApplicationConfiguration[]> {
    const params = new URLSearchParams({ page: '0', size: '200', sort: 'name,asc' });
    if (search.trim()) params.set('name', `~*${search.trim()}*`);
    tagIds.forEach((tagId) => params.append('tagId', String(tagId)));
    const page = await this.request<PageResponse<ApplicationConfiguration>>(
      `/api/configurations?${params.toString()}`,
    );
    return page.content;
  }

  async createConfiguration(request: ConfigurationWriteRequest): Promise<ApplicationConfiguration> {
    return this.request('/api/configurations', { method: 'POST', body: JSON.stringify(request) });
  }

  async updateConfiguration(id: number, request: ConfigurationWriteRequest): Promise<ApplicationConfiguration> {
    return this.request(`/api/configurations/${id}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    });
  }

  async deleteConfiguration(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/configurations/${id}?version=${version}`, { method: 'DELETE' });
  }

  async listTags(): Promise<readonly ConfigurationTag[]> {
    const page = await this.request<PageResponse<ConfigurationTag>>(
      '/api/configuration-tags?page=0&size=200&sort=name,asc',
    );
    return page.content;
  }

  async createTag(request: TagWriteRequest): Promise<ConfigurationTag> {
    return this.request('/api/configuration-tags', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  async updateTag(id: number, request: TagWriteRequest): Promise<ConfigurationTag> {
    return this.request(`/api/configuration-tags/${id}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    });
  }

  async deleteTag(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/configuration-tags/${id}?version=${version}`, { method: 'DELETE' });
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

    if (!response.ok) {
      let message = `Request failed with status ${response.status}.`;
      try {
        const error = (await response.json()) as ApiErrorResponse;
        const fieldMessage = error.fieldErrors ? Object.values(error.fieldErrors)[0] : undefined;
        message = fieldMessage ?? error.message ?? message;
      } catch {
        // Keep the HTTP status message when the response is not JSON.
      }
      throw new Error(message);
    }

    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }
}
