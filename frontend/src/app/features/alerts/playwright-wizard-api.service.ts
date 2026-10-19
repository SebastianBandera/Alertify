import { inject, Injectable } from '@angular/core';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';

export interface WizardSettings {
  url: string;
  steps: string;
  engine: string;
  browsers: string[];
  loadTimeoutSeconds: number;
  elementTimeoutSeconds: number;
  mode: 'ASK' | 'AUTO';
}

export interface WizardState {
  id: string;
  document: {
    settings: WizardSettings;
    allowedProcedureIds?: number[];
    messages: { role: string; content: string; hasImage: boolean; at: string }[];
    proposal?: string;
    validationError?: string;
    testedRevision?: string;
    lastAction?: { operation: string; status?: 'RUNNING' | 'SUCCESS' | 'FAILED'; error?: string; at: string; result?: { completed?: number; failedLine?: number; failedCommand?: string; failureCode?: string } };
    testReport?: { passed: boolean; browsers: { browser: string; passed: boolean; completed: number; error?: string }[] };
  };
  open: boolean;
  activeEngine?: string | null;
  busy: boolean;
  error: string;
  idleRemainingSeconds: number;
  attachmentReady: boolean;
  restartRequired?: boolean;
  selection: { selector: string } | null;
  progress: { completed: number; line: number } | null;
  observation: { snapshot: string } | null;
  tokens: number;
  modelRequests: number;
}

export function playwrightBrowserLabel(engine: string | null | undefined): string {
  return ({ chromium: 'Chromium', firefox: 'Firefox', webkit: 'WebKit' } as Record<string, string>)[engine ?? ''] ?? '';
}

@Injectable({ providedIn: 'root' })
export class PlaywrightWizardApiService {
  private readonly events = inject(AdminEventChannelService);

  async request<T = WizardState>(operation: string, payload: Record<string, unknown> = {}): Promise<T> {
    const result = await this.events.request<T & { requestError?: string }>('PLAYWRIGHT_WIZARD', { ...payload, operation });
    if (result.requestError) throw new Error(result.requestError);
    return result;
  }
}
