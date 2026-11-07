import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { WorkerResourceUsage } from './worker-status-api.service';

export interface BackendStatus {
  readonly name: string;
  readonly instanceId: string;
  readonly startedAt: string;
  readonly uptimeMillis: number;
  readonly sampledAt: string;
  readonly resourceUsage: WorkerResourceUsage;
}

export interface SystemStatusSummary {
  readonly maintenanceModeEnabled: boolean;
  readonly cronQuietHoursActive: boolean;
  readonly activeWorkerCount: number;
  readonly activeAlertExecutions: number;
  readonly waitingAlertExecutions: number;
  readonly activeProcedureExecutions: number;
  readonly waitingProcedureExecutions: number;
  readonly saturatedWorkers: readonly WorkerQueueStatus[];
}

export interface WorkerQueueStatus {
  readonly workerName: string;
  readonly waitingCount: number;
}

@Injectable({ providedIn: 'root' })
export class SystemStatusApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async backend(signal: AbortSignal): Promise<BackendStatus> {
    const token = await this.authService.getAccessToken();
    signal.throwIfAborted();
    const response = await fetch(`${this.apiBaseUrl}/api/system-status/backend`, {
      headers: { Authorization: `Bearer ${token}` }, cache: 'no-store', signal,
    });
    if (!response.ok) throw new Error(`Request failed with status ${response.status}.`);
    return (await response.json()) as BackendStatus;
  }

  async summary(): Promise<SystemStatusSummary> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/system-status/summary`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error(`Request failed with status ${response.status}.`);
    return (await response.json()) as SystemStatusSummary;
  }
}
