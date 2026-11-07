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

export interface WizardBinding { source: 'CONFIGURATION' | 'SECRET' | 'PROCEDURE'; id: number; name: string; version: number; }
export interface WizardSourceAlert { id: number; version: number; form: Record<string, unknown>; }
export interface PreparedWizard { id: string; settings: WizardSettings; sourceAlert?: WizardSourceAlert; parameterBindings?: Record<string, WizardBinding>; }

export interface WizardState {
  id: string;
  document: {
    settings: WizardSettings;
    sourceAlert?: WizardSourceAlert;
    parameterBindings?: Record<string, WizardBinding>;
    allowedProcedureIds?: number[];
    messages: { role: string; content: string; hasImage: boolean; at: string }[];
    proposal?: string;
    proposalError?: { diagnosticCode: string; phase: 'VALIDATION'; status: 'FAILED'; error: string; executed: false; retryable: boolean; remainingCorrections: number; at: string };
    validationError?: string;
    testedRevision?: string;
    lastAction?: { operation: string; status?: 'RUNNING' | 'SUCCESS' | 'FAILED'; error?: string; at: string; result?: { completed?: number; failedLine?: number; failedCommand?: string; failureCode?: string } };
    testReport?: { passed: boolean; browsers: { browser: string; passed: boolean; completed: number; error?: string }[] };
  };
  open: boolean;
  activeEngine?: string | null;
  currentUrl?: string;
  busy: boolean;
  error: string;
  errorCode?: string;
  idleRemainingSeconds: number;
  attachmentReady: boolean;
  maskEnabled?: boolean;
  restartRequired?: boolean;
  selection: { selector: string } | null;
  progress: { completed: number; line: number } | null;
  observation: { snapshot: string } | null;
  tokens: number;
  modelRequests: number;
  parameterConfirmation?: { token: string; procedures: { parameter: string; name: string }[] };
}

export function playwrightBrowserLabel(engine: string | null | undefined): string {
  return ({ chromium: 'Chromium', firefox: 'Firefox', webkit: 'WebKit' } as Record<string, string>)[engine ?? ''] ?? '';
}

export function normalizeWizardUrl(value: string): string {
  const url = value.trim();
  if (!url) return '';
  if (url.startsWith('//')) return 'https:' + url;
  const hostWithPort = /^(?:localhost|[^\s/:?#]+\.[^\s/:?#]+|\[[^\]]+\]):\d+(?:[/?#].*)?$/i.test(url);
  return !hostWithPort && /^[a-z][a-z0-9+.-]*:/i.test(url) ? url : 'https://' + url;
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
