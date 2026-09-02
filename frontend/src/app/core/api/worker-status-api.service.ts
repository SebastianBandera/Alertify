import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { WorkerCapability } from './alert-api.service';

export interface WorkerTaskStatus {
  readonly executionId: string;
  readonly alertId: number;
  readonly alertName: string;
  readonly queuedAt: string;
  readonly workStartedAt: string | null;
  readonly elapsedMillis: number;
}

export interface WorkerNodeStatus {
  readonly address: string;
  readonly available: boolean;
  readonly workerName: string | null;
  readonly workerInstanceId: string | null;
  readonly capabilities: readonly WorkerCapability[];
  readonly totalExecuted: number;
  readonly runningCount: number;
  readonly waitingCount: number;
  readonly maxConcurrentAlerts: number;
  readonly runningTasks: readonly WorkerTaskStatus[];
  readonly waitingTasks: readonly WorkerTaskStatus[];
  readonly error: string | null;
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
}
