import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { ApiRequestError, PageResponse } from './configuration-api.service';

export type PipeStepType = 'ALERT' | 'PROCEDURE';
export type PipeOutcome = 'SUCCESS' | 'WARN' | 'ERROR';
export type PipeExecutionStatus = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED';
export type PipeStepStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'WARN' | 'ERROR'
  | 'SKIPPED_DISABLED' | 'SKIPPED_SEQUENCE' | 'MISSING_PIPE_OUTPUT' | 'ARTIFACT_UNAVAILABLE';

export interface PipeTag {
  readonly id: number;
  readonly version: number;
  readonly scope: 'PIPE';
  readonly name: string;
  readonly color: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface PipeTagWriteRequest {
  readonly version?: number;
  readonly name: string;
  readonly color: string;
}

export interface PipeBinding {
  readonly targetParameterKey: string;
  readonly sourceStepKey: string;
  readonly sourceOutputKey: string;
}

export interface PipeStep {
  readonly id: number | null;
  readonly key: string;
  readonly position: number;
  readonly type: PipeStepType;
  readonly resourceId: number;
  readonly resourceName: string;
  readonly resourceEnabled: boolean;
  readonly timeout: string | null;
  readonly continueOn: readonly PipeOutcome[];
  readonly bindings: readonly PipeBinding[];
}

export interface Pipe {
  readonly id: number;
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly tags: readonly PipeTag[];
  readonly steps: readonly PipeStep[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface PipeOption {
  readonly id: number;
  readonly name: string;
  readonly enabled: boolean;
  readonly type: PipeStepType;
  readonly outputs: readonly string[];
  readonly artifactInputs: readonly string[];
}

export interface PipeOptions {
  readonly resources: readonly PipeOption[];
}

export interface PipeStepWriteRequest {
  readonly key: string;
  readonly type: PipeStepType;
  readonly resourceId: number;
  readonly timeout: string | null;
  readonly continueOn: readonly PipeOutcome[];
  readonly bindings: readonly PipeBinding[];
}

export interface PipeWriteRequest {
  readonly version?: number;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly steps: readonly PipeStepWriteRequest[];
  readonly tagIds: readonly number[];
}

export interface PipeStepResult {
  readonly key: string;
  readonly position: number;
  readonly type: PipeStepType;
  readonly resourceId: number;
  readonly resourceName: string;
  readonly status: PipeStepStatus;
  readonly outcome: PipeOutcome | null;
  readonly resourceExecutionId: string | null;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
  readonly errorCode: string | null;
}

export interface PipeExecution {
  readonly id: number;
  readonly executionId: string;
  readonly pipeId: number;
  readonly pipeName: string;
  readonly pipeVersion: number;
  readonly status: PipeExecutionStatus;
  readonly outcome: PipeOutcome | null;
  readonly trigger: 'MANUAL' | 'HOOK' | 'PROCEDURE';
  readonly rootExecutionId: string;
  readonly parentProcedureExecutionId: string | null;
  readonly depth: number;
  readonly startedAt: string;
  readonly finishedAt: string | null;
  readonly durationMillis: number | null;
  readonly triggeredBy: string | null;
  readonly errorCode: string | null;
  readonly steps: readonly PipeStepResult[];
}

export interface PipeDeletionImpact {
  readonly executionCount: number;
  readonly hookReferenceCount: number;
  readonly procedureReferenceCount: number;
}

export interface PipeImportResult {
  readonly total: number;
  readonly created: number;
  readonly updated: number;
  readonly unchanged: number;
}

interface ApiErrorResponse {
  readonly code?: string;
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
  readonly parameters?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class PipeApiService {
  private readonly auth = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  list(name = '', page = 0, size = 20): Promise<PageResponse<Pipe>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'name,asc' });
    if (name.trim()) params.set('name', name.trim());
    return this.request(`/api/pipes?${params}`);
  }

  options(): Promise<PipeOptions> { return this.request('/api/pipes/options'); }

  async listTags(): Promise<readonly PipeTag[]> {
    const page = await this.request<PageResponse<PipeTag>>('/api/pipe-tags?page=0&size=200&sort=name,asc');
    return page.content;
  }

  createTag(request: PipeTagWriteRequest): Promise<PipeTag> {
    return this.request('/api/pipe-tags', { method: 'POST', body: JSON.stringify(request) });
  }

  updateTag(id: number, request: PipeTagWriteRequest): Promise<PipeTag> {
    return this.request(`/api/pipe-tags/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async deleteTag(tag: PipeTag): Promise<void> {
    await this.request<void>(`/api/pipe-tags/${tag.id}?version=${tag.version}`, { method: 'DELETE' }, true);
  }

  create(request: PipeWriteRequest): Promise<Pipe> {
    return this.request('/api/pipes', { method: 'POST', body: JSON.stringify(request) });
  }

  update(id: number, request: PipeWriteRequest): Promise<Pipe> {
    return this.request(`/api/pipes/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  run(id: number): Promise<{ readonly executionId: string }> {
    return this.request(`/api/pipes/${id}/run`, { method: 'POST' });
  }

  deletionImpact(id: number): Promise<PipeDeletionImpact> {
    return this.request(`/api/pipes/${id}/deletion-impact`);
  }

  async delete(pipe: Pipe): Promise<void> {
    await this.request<void>(`/api/pipes/${pipe.id}?version=${pipe.version}`, { method: 'DELETE' }, true);
  }

  history(pipeId: number | null, page = 0, size = 20): Promise<PageResponse<PipeExecution>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'startedAt,desc' });
    if (pipeId !== null) params.set('pipeId', String(pipeId));
    return this.request(`/api/pipe-executions?${params}`);
  }

  execution(executionId: string): Promise<PipeExecution> {
    return this.request(`/api/pipe-executions/${encodeURIComponent(executionId)}`);
  }

  async exportCsv(): Promise<Blob> {
    const token = await this.auth.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/pipes/export`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw await this.responseError(response);
    return response.blob();
  }

  async importCsv(file: File): Promise<PipeImportResult> {
    const token = await this.auth.getAccessToken();
    const body = new FormData();
    body.append('file', file);
    const response = await fetch(`${this.apiBaseUrl}/api/pipes/import`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` }, body,
    });
    if (!response.ok) throw await this.responseError(response);
    return response.json();
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
