import { inject, Injectable } from '@angular/core';

import { ApiRequestError, PageResponse, TagMatchMode, TagWriteRequest } from './configuration-api.service';
import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';

export interface SecretTag {
  readonly id: number;
  readonly version: number;
  readonly scope: 'SECRET';
  readonly name: string;
  readonly color: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ApplicationSecret {
  readonly id: number;
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly tags: readonly SecretTag[];
  readonly recoveryStatus: 'RECOVERABLE' | 'UNRECOVERABLE';
  readonly valueRevision: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SecretCreateRequest {
  readonly name: string;
  readonly description: string | null;
  readonly value: string;
  readonly tagIds: readonly number[];
}

export interface SecretUpdateRequest {
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly newValue: string;
  readonly tagIds: readonly number[];
}

interface ApiErrorResponse {
  readonly code?: string;
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
  readonly parameters?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class SecretApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async listSecrets(search: string, tagIds: readonly number[], tagMatchMode: TagMatchMode, pageNumber: number, pageSize: number): Promise<PageResponse<ApplicationSecret>> {
    const params = new URLSearchParams({ page: String(pageNumber), size: String(pageSize), sort: 'name,asc' });
    if (search.trim()) params.set('name', `~*${search.trim()}*`);
    tagIds.forEach((tagId) => params.append('tagId', String(tagId)));
    if (tagIds.length >= 2) params.set('tagOperator', tagMatchMode);
    return this.request<PageResponse<ApplicationSecret>>(`/api/secrets?${params.toString()}`);
  }

  async createSecret(request: SecretCreateRequest): Promise<ApplicationSecret> {
    return this.request('/api/secrets', { method: 'POST', body: JSON.stringify(request) });
  }

  async updateSecret(id: number, request: SecretUpdateRequest): Promise<ApplicationSecret> {
    return this.request(`/api/secrets/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async deleteSecret(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/secrets/${id}?version=${version}`, { method: 'DELETE' });
  }

  async listTags(): Promise<readonly SecretTag[]> {
    const page = await this.request<PageResponse<SecretTag>>('/api/secret-tags?page=0&size=200&sort=name,asc');
    return page.content;
  }

  async createTag(request: TagWriteRequest): Promise<SecretTag> {
    return this.request('/api/secret-tags', { method: 'POST', body: JSON.stringify(request) });
  }

  async updateTag(id: number, request: TagWriteRequest): Promise<SecretTag> {
    return this.request(`/api/secret-tags/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async deleteTag(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/secret-tags/${id}?version=${version}`, { method: 'DELETE' });
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await this.authService.getAccessToken();
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    if (init.body) headers.set('Content-Type', 'application/json');
    const response = await fetch(`${this.apiBaseUrl}${path}`, { ...init, headers });
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
      return new ApiRequestError(message, error.code, error.parameters);
    } catch {
      return new Error(message);
    }
  }
}
