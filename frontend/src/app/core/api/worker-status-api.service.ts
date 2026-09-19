import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { WorkerCapability } from './alert-api.service';

export interface WorkerTaskStatus {
  readonly executionId: string;
  readonly kind: 'ALERT' | 'PROCEDURE';
  readonly resourceId: number;
  readonly resourceName: string;
  readonly parentExecutionId: string | null;
  readonly depth: number;
  readonly queuedAt: string;
  readonly workStartedAt: string | null;
  readonly elapsedMillis: number;
}

export interface WorkerResourceUsage {
  readonly memoryUsedBytes: number | null;
  readonly memoryMaxBytes: number | null;
  readonly heapUsedBytes: number | null;
  readonly heapMaxBytes: number | null;
  readonly cpuUsage: number | null;
  readonly availableProcessors: number;
}

export interface WorkerNodeStatus {
  readonly address: string;
  readonly available: boolean;
  readonly workerName: string | null;
  readonly workerInstanceId: string | null;
  readonly workerStartedAt: string | null;
  readonly capabilities: readonly WorkerCapability[];
  readonly totalExecuted: number;
  readonly runningCount: number;
  readonly waitingCount: number;
  readonly maxConcurrentAlerts: number;
  readonly runningTasks: readonly WorkerTaskStatus[];
  readonly waitingTasks: readonly WorkerTaskStatus[];
  readonly totalExecutedProcedures: number;
  readonly runningProcedureCount: number;
  readonly runningProcedures: readonly WorkerTaskStatus[];
  readonly resourceUsage: WorkerResourceUsage | null;
  readonly error: string | null;
}

export interface WorkerActivitySeries {
  readonly workerInstanceId: string;
  readonly workerName: string | null;
  readonly address: string | null;
  readonly kind: 'ALERT' | 'PROCEDURE';
  readonly values: readonly number[];
}

export interface WorkerActivityHistory {
  readonly from: string;
  readonly to: string;
  readonly series: readonly WorkerActivitySeries[];
}

@Injectable({ providedIn: 'root' })
export class WorkerStatusApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async status(): Promise<readonly WorkerNodeStatus[]> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/workers/status`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}.`);
    }
    return (await response.json()) as readonly WorkerNodeStatus[];
  }

  async history(): Promise<WorkerActivityHistory> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/workers/status/history`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}.`);
    }
    return (await response.json()) as WorkerActivityHistory;
  }
}
