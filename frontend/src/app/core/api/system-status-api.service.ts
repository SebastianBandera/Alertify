import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';

export interface SystemStatusSummary {
  readonly maintenanceModeEnabled: boolean;
  readonly activeAlertExecutions: number;
  readonly waitingAlertExecutions: number;
  readonly activeProcedureExecutions: number;
  readonly waitingProcedureExecutions: number;
}

@Injectable({ providedIn: 'root' })
export class SystemStatusApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async summary(): Promise<SystemStatusSummary> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/system-status/summary`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error(`Request failed with status ${response.status}.`);
    return (await response.json()) as SystemStatusSummary;
  }
}
