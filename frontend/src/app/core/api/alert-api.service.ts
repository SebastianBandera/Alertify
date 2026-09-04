import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { ApiRequestError, PageResponse, TagMatchMode } from './configuration-api.service';

export type AlertParameterSource = 'TEXT' | 'CONFIGURATION' | 'SECRET';
export type AlertExecutionStatus = 'SUCCESS' | 'WARN' | 'ERROR';
export type WorkerCapability = 'STANDARD' | 'PLAYWRIGHT';

export interface AlertTag {
  readonly id: number;
  readonly version: number;
  readonly scope: 'ALERT';
  readonly name: string;
  readonly color: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AlertTemplateParameter {
  readonly id: number;
  readonly version: number;
  readonly key: string;
  readonly labelKey: string;
  readonly descriptionKey: string;
  readonly javaType: string;
  readonly options: readonly string[];
  readonly bindingAllowed: boolean;
  readonly defaultValue: string | null;
  readonly order: number;
  readonly required: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AlertTemplateTag {
  readonly nameKey: string;
  readonly color: string | null;
}

export interface AlertTemplate {
  readonly id: number;
  readonly version: number;
  readonly templateKey: string;
  readonly nameKey: string;
  readonly descriptionKey: string;
  readonly requiredCapability: WorkerCapability;
  readonly tags: readonly AlertTemplateTag[];
  readonly alertCount: number;
  readonly parameters: readonly AlertTemplateParameter[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AlertParameterValue {
  readonly id: number;
  readonly version: number;
  readonly parameterKey: string;
  readonly source: AlertParameterSource;
  readonly textValue: string | null;
  readonly configurationId: number | null;
  readonly configurationName: string | null;
  readonly secretId: number | null;
  readonly secretName: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface Alert {
  readonly id: number;
  readonly version: number;
  readonly templateId: number;
  readonly templateKey: string;
  readonly templateNameKey: string;
  readonly name: string;
  readonly description: string | null;
  readonly cronExpression: string;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly tags: readonly AlertTag[];
  readonly parameters: readonly AlertParameterValue[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AlertParameterWriteRequest {
  readonly parameterKey: string;
  readonly source: AlertParameterSource;
  readonly textValue: string | null;
  readonly configurationId: number | null;
  readonly secretId: number | null;
}

export interface AlertWriteRequest {
  readonly templateId?: number;
  readonly version?: number;
  readonly name: string;
  readonly description: string | null;
  readonly cronExpression: string;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly tagIds: readonly number[];
  readonly parameters: readonly AlertParameterWriteRequest[];
}

export interface AlertTagWriteRequest {
  readonly version?: number;
  readonly name: string;
  readonly color: string;
}

export interface AlertBindingOption {
  readonly id: number;
  readonly name: string;
  readonly description: string | null;
}

export interface AlertBindingOptions {
  readonly configurations: readonly AlertBindingOption[];
  readonly secrets: readonly AlertBindingOption[];
}

export interface AlertExecution {
  readonly id: number;
  readonly executionId: string;
  readonly alertId: number;
  readonly alertName: string;
  readonly status: AlertExecutionStatus;
  readonly startedAt: string;
  readonly workStartedAt: string;
  readonly finishedAt: string;
  readonly durationMillis: number;
  readonly idleMillis: number;
  readonly executionMillis: number;
  readonly statusMessage: unknown | null;
  readonly errorType: string | null;
  readonly errorMessage: string | null;
  readonly workerName: string | null;
  readonly workerIpAddress: string | null;
  readonly workerPort: number | null;
  readonly workerInstanceId: string | null;
}

export interface AlertDeletionImpact {
  readonly alertId: number;
  readonly name: string;
  readonly executionCount: number;
}

export interface AlertImportResult {
  readonly total: number;
  readonly created: number;
  readonly updated: number;
  readonly unchanged: number;
  readonly tagsCreated: number;
}

interface ApiErrorResponse {
  readonly code?: string;
  readonly message?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
  readonly parameters?: Readonly<Record<string, string>>;
}

@Injectable({ providedIn: 'root' })
export class AlertApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async listAlerts(
    name: string,
    templateId: number | null,
    tagIds: readonly number[],
    tagMatchMode: TagMatchMode,
    page: number,
    size: number,
  ): Promise<PageResponse<Alert>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'name,asc' });
    if (name.trim()) params.set('name', name.trim());
    if (templateId !== null) params.set('templateId', String(templateId));
    tagIds.forEach((tagId) => params.append('tagId', String(tagId)));
    if (tagIds.length >= 2) params.set('tagOperator', tagMatchMode);
    return this.request(`/api/alerts?${params.toString()}`);
  }

  async alertDeletionImpact(id: number): Promise<AlertDeletionImpact> {
    return this.request(`/api/alerts/${id}/deletion-impact`);
  }

  // Answers 202 Accepted with no body, which the shared request helper only tolerates on 204.
  async runAlertNow(id: number): Promise<void> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/alerts/${id}/run`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw await this.responseError(response);
  }

  async exportAlerts(): Promise<Blob> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/alerts/export`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw await this.responseError(response);
    return response.blob();
  }

  async importAlerts(file: File): Promise<AlertImportResult> {
    const token = await this.authService.getAccessToken();
    const body = new FormData();
    body.append('file', file);
    const response = await fetch(`${this.apiBaseUrl}/api/alerts/import`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body,
    });
    if (!response.ok) throw await this.responseError(response);
    return (await response.json()) as AlertImportResult;
  }

  async listTags(): Promise<readonly AlertTag[]> {
    const page = await this.request<PageResponse<AlertTag>>('/api/alert-tags?page=0&size=200&sort=name,asc');
    return page.content;
  }

  async createTag(request: AlertTagWriteRequest): Promise<AlertTag> {
    return this.request('/api/alert-tags', { method: 'POST', body: JSON.stringify(request) });
  }

  async updateTag(id: number, request: AlertTagWriteRequest): Promise<AlertTag> {
    return this.request(`/api/alert-tags/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async deleteTag(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/alert-tags/${id}?version=${version}`, { method: 'DELETE' });
  }

  async listTemplates(): Promise<readonly AlertTemplate[]> {
    return this.request('/api/alert-templates');
  }

  async bindingOptions(): Promise<AlertBindingOptions> {
    return this.request('/api/alerts/binding-options');
  }

  async listExecutions(
    alertId: number | null,
    status: AlertExecutionStatus | '',
    page: number,
    size: number,
  ): Promise<PageResponse<AlertExecution>> {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort: 'startedAt,desc' });
    if (alertId !== null) params.set('alertId', String(alertId));
    if (status) params.set('status', status);
    return this.request(`/api/alert-executions?${params.toString()}`);
  }

  async createAlert(request: AlertWriteRequest): Promise<Alert> {
    return this.request('/api/alerts', { method: 'POST', body: JSON.stringify(request) });
  }

  async updateAlert(id: number, request: AlertWriteRequest): Promise<Alert> {
    return this.request(`/api/alerts/${id}`, { method: 'PUT', body: JSON.stringify(request) });
  }

  async deleteAlert(id: number, version: number): Promise<void> {
    await this.request<void>(`/api/alerts/${id}?version=${version}`, { method: 'DELETE' });
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
      return new ApiRequestError(message, error.code, error.parameters, error.fieldErrors);
    } catch {
      return new Error(message);
    }
  }
}
