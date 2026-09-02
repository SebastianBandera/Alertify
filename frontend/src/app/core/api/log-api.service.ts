import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { PageResponse } from './configuration-api.service';

export type ApplicationLogLevel = 'INFO' | 'WARN' | 'ERROR';
export type ApplicationLogOutcome = 'SUCCESS' | 'FAILURE';

export interface ApplicationLog {
  readonly id: number;
  readonly eventAt: string;
  readonly level: ApplicationLogLevel;
  readonly source: string;
  readonly event: string;
  readonly outcome: ApplicationLogOutcome;
  readonly userSubject: string;
  readonly username: string;
  readonly requestId: string | null;
  readonly path: string | null;
  readonly data: Readonly<Record<string, unknown>>;
}

export interface ApplicationLogFilters {
  readonly username: string;
  readonly subject: string;
  readonly event: string;
  readonly path: string;
  readonly level: '' | ApplicationLogLevel;
  readonly outcome: '' | ApplicationLogOutcome;
  readonly from: string;
  readonly to: string;
}

@Injectable({ providedIn: 'root' })
export class LogApiService {
  private readonly authService = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  async list(
    filters: ApplicationLogFilters,
    pageNumber: number,
    pageSize: number,
  ): Promise<PageResponse<ApplicationLog>> {
    const params = new URLSearchParams({
      page: String(pageNumber),
      size: String(pageSize),
      sort: 'eventAt,desc',
    });
    if (filters.username.trim()) params.set('user', `~*${filters.username.trim()}*`);
    if (filters.subject.trim()) params.set('subject', `~*${filters.subject.trim()}*`);
    if (filters.event.trim()) params.set('event', `~*${filters.event.trim()}*`);
    if (filters.path.trim()) params.set('path', `~*${filters.path.trim()}*`);
    if (filters.level) params.set('level', filters.level);
    if (filters.outcome) params.set('outcome', filters.outcome);

    const from = this.toInstant(filters.from);
    const to = this.toInstant(filters.to);
    if (from && to) params.set('eventAt', `${from}_${to}`);
    else if (from) params.set('eventAt', `>=${from}`);
    else if (to) params.set('eventAt', `<=${to}`);

    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/logs?${params.toString()}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error(`Request failed with status ${response.status}.`);
    return (await response.json()) as PageResponse<ApplicationLog>;
  }

  async recordLogout(): Promise<void> {
    const token = await this.authService.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}/api/logs/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      throw new Error(`Logout event failed with status ${response.status}.`);
    }
  }

  private toInstant(value: string): string | null {
    if (!value) return null;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }
}
