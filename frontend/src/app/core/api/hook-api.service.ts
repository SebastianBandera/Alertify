import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { ApiRequestError, PageResponse } from './configuration-api.service';

export type HookMode = 'PARALLEL' | 'SEQUENTIAL';
export type HookTargetType = 'ALERT' | 'PROCEDURE';
export type HookOutcome = 'SUCCESS' | 'WARN' | 'ERROR';
export type HookInvocationStatus = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED';
export type HookTargetStatus = 'PENDING' | 'WAITING_ALERT' | 'WAITING_PROCEDURE' | 'RUNNING' | HookOutcome
  | 'SKIPPED_DISABLED' | 'SKIPPED_SEQUENCE' | 'ALERT_BUSY_TIMEOUT' | 'PROCEDURE_BUSY_TIMEOUT';

export interface HookTarget {
  readonly id: number;
  readonly type: HookTargetType;
  readonly resourceId: number;
  readonly resourceName: string;
  readonly enabled: boolean;
  readonly position: number;
  readonly continueOn: readonly HookOutcome[];
  readonly busyWaitTimeout: string | null;
}

export interface Hook {
  readonly id: number;
  readonly version: number;
  readonly publicId: string;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly mode: HookMode;
  readonly tokenSecretId: number | null;
  readonly tokenSecretName: string | null;
  readonly maxConcurrentInvocations: number | null;
  readonly rateLimitCount: number | null;
  readonly rateLimitWindow: string | null;
  readonly targets: readonly HookTarget[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface HookOption {
  readonly id: number;
  readonly name: string;
  readonly enabled: boolean;
  readonly type: HookTargetType;
}

export interface HookSecretOption {
  readonly id: number;
  readonly name: string;
  readonly recoverable: boolean;
}

export interface HookOptions {
  readonly targets: readonly HookOption[];
  readonly secrets: readonly HookSecretOption[];
}

export interface HookTargetWriteRequest {
  readonly type: HookTargetType;
  readonly resourceId: number;
  readonly continueOn: readonly HookOutcome[];
  readonly busyWaitTimeout: string | null;
}

export interface HookWriteRequest {
  readonly version?: number;
  readonly name: string;
  readonly description: string | null;
  readonly enabled?: boolean;
  readonly mode: HookMode;
  readonly tokenSecretId: number | null;
  readonly maxConcurrentInvocations: number | null;
  readonly rateLimitCount: number | null;
  readonly rateLimitWindow: string | null;
  readonly targets: readonly HookTargetWriteRequest[];
}

export interface HookInvocationTarget {
  readonly type: HookTargetType;
  readonly resourceName: string;
  readonly position: number;
  readonly status: HookTargetStatus;
  readonly executionId: string | null;
}

export interface HookInvocation {
  readonly invocationId: string;
  readonly hookPublicId: string;
  readonly hookName: string;
  readonly mode: HookMode;
  readonly status: HookInvocationStatus;
  readonly acceptedAt: string;
  readonly finishedAt: string | null;
  readonly targets: readonly HookInvocationTarget[];
}

export interface HookDeletionImpact {
  readonly invocationCount: number;
  readonly targetResultCount: number;
}

interface ApiErrorResponse {
  readonly code?: string;
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
  readonly parameters?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class HookApiService {
  private readonly auth = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  list(name = '', page = 0, size = 20): Promise<PageResponse<Hook>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'name,asc' });
    if (name.trim()) params.set('name', name.trim());
    return this.request(`/api/hooks?${params}`);
  }

  options(): Promise<HookOptions> { return this.request('/api/hooks/options'); }

  create(request: HookWriteRequest): Promise<Hook> {
    return this.request('/api/hooks', { method: 'POST', body: JSON.stringify(request) });
  }

  update(id: number, request: HookWriteRequest): Promise<Hook> {
    return this.request(`/api/hooks/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  rotate(hook: Hook): Promise<Hook> {
    return this.request(`/api/hooks/${hook.id}/rotate?version=${hook.version}`, { method: 'POST' });
  }

  deletionImpact(id: number): Promise<HookDeletionImpact> {
    return this.request(`/api/hooks/${id}/deletion-impact`);
  }

  async delete(hook: Hook): Promise<void> {
    await this.request<void>(`/api/hooks/${hook.id}?version=${hook.version}`, { method: 'DELETE' }, true);
  }

  history(hookId: number, page = 0, size = 20): Promise<PageResponse<HookInvocation>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'acceptedAt,desc' });
    return this.request(`/api/hooks/${hookId}/invocations?${params}`);
  }

  invocationUrl(publicId: string): string {
    return new URL(`${this.apiBaseUrl}/api/hooks/${encodeURIComponent(publicId)}/invoke`, window.location.origin).toString();
  }

  private async request<T>(path: string, init: RequestInit = {}, acceptEmpty = false): Promise<T> {
    const token = await this.auth.getAccessToken();
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    if (init.body) headers.set('Content-Type', 'application/json');
    const response = await fetch(`${this.apiBaseUrl}${path}`, { ...init, headers });
    if (!response.ok) throw await this.responseError(response);
    if (acceptEmpty || response.status === 204) return undefined as T;
    return response.json();
  }

  private async responseError(response: Response): Promise<Error> {
    let message = `Request failed with status ${response.status}.`;
    try {
      const error = (await response.json()) as ApiErrorResponse;
      const fieldMessage = error.fieldErrors ? Object.values(error.fieldErrors)[0] : undefined;
      message = fieldMessage ?? error.message ?? message;
      return new ApiRequestError(message, error.code, error.parameters, error.fieldErrors);
    } catch {
      return new Error(message);
    }
  }
}
