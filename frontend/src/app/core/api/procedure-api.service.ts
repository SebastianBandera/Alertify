import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { AlertParameterSource, WorkerCapability } from './alert-api.service';
import { ApiRequestError, PageResponse, TagMatchMode } from './configuration-api.service';

export type ProcedureExecutionStatus = 'RUNNING' | 'COMPLETED' | 'ERROR';

export interface ProcedureTag {
  readonly id: number;
  readonly version: number;
  readonly scope: 'PROCEDURE';
  readonly name: string;
  readonly color: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ProcedureTemplateParameter {
  readonly id: number;
  readonly version: number;
  readonly key: string;
  readonly labelKey: string;
  readonly descriptionKey: string;
  readonly javaType: string;
  readonly options: readonly string[];
  readonly bindingAllowed: boolean;
  readonly defaultValue: string | null;
  readonly multiline: boolean;
  readonly order: number;
  readonly required: boolean;
  readonly allowedSources: readonly AlertParameterSource[];
}

export interface ProcedureTemplate {
  readonly id: number;
  readonly version: number;
  readonly templateKey: string;
  readonly nameKey: string;
  readonly descriptionKey: string;
  readonly requiredCapability: WorkerCapability;
  readonly sensitiveResult: boolean;
  readonly tags: readonly { nameKey: string; color: string | null }[];
  readonly procedureCount: number;
  readonly parameters: readonly ProcedureTemplateParameter[];
}

export interface ProcedureParameterValue {
  readonly id: number;
  readonly version: number;
  readonly parameterKey: string;
  readonly source: AlertParameterSource;
  readonly textValue: string | null;
  readonly configurationId: number | null;
  readonly configurationName: string | null;
  readonly secretId: number | null;
  readonly secretName: string | null;
  readonly procedureId: number | null;
  readonly procedureName: string | null;
}

export interface Procedure {
  readonly id: number;
  readonly version: number;
  readonly templateId: number;
  readonly templateKey: string;
  readonly templateNameKey: string;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly tags: readonly ProcedureTag[];
  readonly parameters: readonly ProcedureParameterValue[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ProcedureParameterWriteRequest {
  readonly parameterKey: string;
  readonly source: AlertParameterSource;
  readonly textValue: string | null;
  readonly configurationId: number | null;
  readonly secretId: number | null;
  readonly procedureId: number | null;
}

export interface ProcedureWriteRequest {
  readonly templateId?: number;
  readonly version?: number;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly tagIds: readonly number[];
  readonly parameters: readonly ProcedureParameterWriteRequest[];
}

export interface ProcedureBindingOption {
  readonly id: number;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
}

export interface ProcedureBindingOptions {
  readonly configurations: readonly ProcedureBindingOption[];
  readonly secrets: readonly ProcedureBindingOption[];
  readonly procedures: readonly ProcedureBindingOption[];
}

export interface ProcedureExecution {
  readonly id: number;
  readonly executionId: string;
  readonly procedureId: number;
  readonly procedureName: string;
  readonly procedureVersion: number;
  readonly status: ProcedureExecutionStatus;
  readonly trigger: 'MANUAL' | 'ALERT' | 'PROCEDURE' | 'HOOK';
  readonly rootExecutionId: string;
  readonly parentAlertExecutionId: string | null;
  readonly parentProcedureExecutionId: string | null;
  readonly depth: number;
  readonly startedAt: string;
  readonly workStartedAt: string | null;
  readonly finishedAt: string | null;
  readonly durationMillis: number | null;
  readonly result: unknown | null;
  readonly resultRedacted: boolean;
  readonly errorType: string | null;
  readonly errorMessage: string | null;
  readonly workerName: string | null;
}

export interface ProcedureImportResult {
  readonly total: number;
  readonly created: number;
  readonly updated: number;
  readonly unchanged: number;
  readonly tagsCreated: number;
}

export interface TotpQrAnalysisResult {
  readonly secretId: number;
  readonly secretName: string;
  readonly algorithm: 'SHA1' | 'SHA256' | 'SHA512';
  readonly digits: number;
  readonly periodSeconds: number;
  readonly suggestedProcedureName: string;
}

interface ApiErrorResponse {
  readonly code?: string;
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
  readonly parameters?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class ProcedureApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async listProcedures(name = '', templateId: number | null = null, tagIds: readonly number[] = [],
      tagMatchMode: TagMatchMode = 'OR', page = 0, size = 20): Promise<PageResponse<Procedure>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'name,asc' });
    if (name.trim()) params.set('name', name.trim());
    if (templateId !== null) params.set('templateId', String(templateId));
    tagIds.forEach((tagId) => params.append('tagId', String(tagId)));
    if (tagIds.length >= 2) params.set('tagOperator', tagMatchMode);
    return this.request(`/api/procedures?${params}`);
  }

  async listTemplates(): Promise<readonly ProcedureTemplate[]> {
    return this.request('/api/procedure-templates');
  }

  async listTags(): Promise<readonly ProcedureTag[]> {
    const page = await this.request<PageResponse<ProcedureTag>>('/api/procedure-tags?page=0&size=200&sort=name,asc');
    return page.content;
  }

  async createTag(name: string, color: string): Promise<ProcedureTag> {
    return this.request('/api/procedure-tags', { method: 'POST', body: JSON.stringify({ name, color }) });
  }

  async updateTag(tag: ProcedureTag, name: string, color: string): Promise<ProcedureTag> {
    return this.request(`/api/procedure-tags/${tag.id}`, {
      method: 'PUT', body: JSON.stringify({ version: tag.version, name, color }),
    });
  }

  async deleteTag(tag: ProcedureTag): Promise<void> {
    await this.request<void>(`/api/procedure-tags/${tag.id}?version=${tag.version}`, { method: 'DELETE' });
  }

  async bindingOptions(): Promise<ProcedureBindingOptions> {
    return this.request('/api/procedures/binding-options');
  }

  async listExecutions(procedureId: number | null, status: ProcedureExecutionStatus | '', page = 0, size = 20, executionId: string | null = null): Promise<PageResponse<ProcedureExecution>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'startedAt,desc' });
    if (procedureId !== null) params.set('procedureId', String(procedureId));
    if (status) params.set('status', status);
    if (executionId) params.set('executionId', executionId);
    return this.request(`/api/procedure-executions?${params}`);
  }

  async createProcedure(request: ProcedureWriteRequest): Promise<Procedure> {
    return this.request('/api/procedures', { method: 'POST', body: JSON.stringify(request) });
  }

  async updateProcedure(id: number, request: ProcedureWriteRequest): Promise<Procedure> {
    return this.request(`/api/procedures/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async runProcedure(id: number): Promise<void> {
    await this.request<void>(`/api/procedures/${id}/run`, { method: 'POST' }, true);
  }

  async deleteProcedure(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/procedures/${id}?version=${version}`, { method: 'DELETE' });
  }

  async exportProcedures(): Promise<Blob> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/procedures/export`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw await this.responseError(response);
    return response.blob();
  }

  async importProcedures(file: File): Promise<ProcedureImportResult> {
    const token = await this.authService.getAccessToken();
    const body = new FormData();
    body.append('file', file);
    const response = await fetch(`${this.apiBaseUrl}/api/procedures/import`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` }, body,
    });
    if (!response.ok) throw await this.responseError(response);
    return response.json();
  }

  async analyzeTotpQr(file: File): Promise<TotpQrAnalysisResult> {
    const token = await this.authService.getAccessToken();
    const body = new FormData();
    body.append('file', file);
    const response = await fetch(`${this.apiBaseUrl}/api/procedures/wizards/totp/qr`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` }, body,
    });
    if (!response.ok) throw await this.responseError(response);
    return response.json();
  }

  private async request<T>(path: string, init: RequestInit = {}, acceptEmpty = false): Promise<T> {
    const token = await this.authService.getAccessToken();
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    if (init.body) headers.set('Content-Type', 'application/json');
    const response = await fetch(`${this.apiBaseUrl}${path}`, { ...init, headers });
    if (!response.ok) throw await this.responseError(response);
    if (response.status === 204 || acceptEmpty) return undefined as T;
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
