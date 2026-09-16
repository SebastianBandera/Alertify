import { inject, Injectable } from '@angular/core';

import { ApiRequestError, BinaryLimits, PageResponse, TagMatchMode, TagWriteRequest } from './configuration-api.service';
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

export type SecretValueType = 'STRING' | 'DB_SECRET' | 'GIT_SECRET' | 'EXPRESSION' | 'BINARY';

export type DatabaseEngine = 'POSTGRESQL' | 'MARIADB' | 'SQL_SERVER' | 'ORACLE' | 'OTHER';

export type GitProvider = 'GITHUB' | 'GITLAB' | 'BITBUCKET' | 'OTHER';

export const SECRET_VALUE_TYPES: readonly SecretValueType[] = ['STRING', 'DB_SECRET', 'GIT_SECRET', 'EXPRESSION', 'BINARY'];

export const DATABASE_ENGINES: readonly DatabaseEngine[] = ['POSTGRESQL', 'MARIADB', 'SQL_SERVER', 'ORACLE', 'OTHER'];

export const GIT_PROVIDERS: readonly GitProvider[] = ['GITHUB', 'GITLAB', 'BITBUCKET', 'OTHER'];

/** Value shape sent for DB_SECRET secrets; the backend stores it as canonical JSON. */
export interface DatabaseSecretValue {
  readonly engine: DatabaseEngine;
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly username: string;
  readonly password: string;
  readonly options: string | null;
}

/** Value shape sent for GIT_SECRET secrets; the backend stores it as canonical JSON. */
export interface GitSecretValue {
  readonly provider: GitProvider;
  readonly host: string;
  readonly username: string | null;
  readonly token: string;
  readonly tokenExpiresAt: string | null;
}

export type SecretValue = string | DatabaseSecretValue | GitSecretValue;

export interface SecretExpressionSuggestions {
  readonly configurations: readonly string[];
  readonly secrets: readonly string[];
  readonly environmentVariables: readonly string[];
  readonly utilities: readonly string[];
  readonly utilityFunctions: readonly string[];
}

export interface SecretExpressionValidationRequest {
  readonly secretId?: number;
  readonly name?: string;
  readonly expression: string;
}

export interface ApplicationSecret {
  readonly id: number;
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly valueType: SecretValueType;
  readonly binaryFileName: string | null;
  readonly binaryContentType: string | null;
  readonly binarySize: number | null;
  readonly binaryZipSize: number | null;
  readonly tags: readonly SecretTag[];
  readonly writable: boolean;
  readonly recoveryStatus: 'RECOVERABLE' | 'UNRECOVERABLE';
  readonly valueRevision: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SecretCreateRequest {
  readonly name: string;
  readonly description: string | null;
  readonly valueType: SecretValueType;
  readonly value: SecretValue;
  readonly tagIds: readonly number[];
  readonly writable: boolean;
}

export interface SecretUpdateRequest {
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly valueType: SecretValueType;
  readonly newValue: SecretValue;
  readonly tagIds: readonly number[];
  readonly writable: boolean;
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

  async getBinaryLimits(): Promise<BinaryLimits> {
    return this.request('/api/binary-values/limits');
  }

  async updateSecret(id: number, request: SecretUpdateRequest): Promise<ApplicationSecret> {
    return this.request(`/api/secrets/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async createBinarySecret(metadata: Omit<SecretCreateRequest, 'valueType' | 'value'>, file: File | null): Promise<ApplicationSecret> {
    return this.binaryRequest('/api/secrets', 'POST', metadata, file);
  }

  async updateBinarySecret(id: number, metadata: Omit<SecretUpdateRequest, 'valueType' | 'newValue'>, file: File | null): Promise<ApplicationSecret> {
    return this.binaryRequest(`/api/secrets/${id}`, 'PUT', metadata, file);
  }

  private async binaryRequest(path: string, method: string, metadata: object, file: File | null): Promise<ApplicationSecret> {
    const token = await this.authService.getAccessToken();
    const body = new FormData();
    body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    if (file) body.append('file', file);
    const response = await fetch(`${this.apiBaseUrl}${path}`, { method, headers: { Authorization: `Bearer ${token}` }, body });
    if (!response.ok) throw await this.responseError(response);
    return (await response.json()) as ApplicationSecret;
  }

  async deleteSecret(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/secrets/${id}?version=${version}`, { method: 'DELETE' });
  }

  async getExpressionSuggestions(): Promise<SecretExpressionSuggestions> {
    return this.request('/api/secrets/expression-suggestions');
  }

  /** Resolves when the draft is valid; the evaluated value is never returned. */
  async validateExpression(request: SecretExpressionValidationRequest): Promise<void> {
    await this.request<void>('/api/secrets/validate-expression', { method: 'POST', body: JSON.stringify(request) });
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
