import { inject, Injectable } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { AppLocale } from '../i18n/localization.types';

export type AiChatMode = 'READ_ONLY' | 'PLAN' | 'DIRECT';
export type AiApprovalPolicy = 'ASK' | 'AUTO';
export const AI_REASONING_EFFORTS = ['low', 'medium', 'high', 'xhigh'] as const;
export type AiReasoningEffort = typeof AI_REASONING_EFFORTS[number];

export class AiChatApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    message: string,
  ) {
    super(message);
    this.name = 'AiChatApiError';
  }
}

export interface AiConversationSummary {
  readonly id: number;
  readonly title: string;
  readonly mode: AiChatMode;
  readonly approvalPolicy: AiApprovalPolicy;
  readonly status: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AiChatMessage {
  readonly id: number;
  readonly turnId: number | null;
  readonly role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';
  readonly content: string;
  readonly createdAt: string;
}

export interface AiToolInvocation {
  readonly id: number;
  readonly callId: string;
  readonly generation: number;
  readonly toolName: string;
  readonly kind: 'DOMAIN' | 'INTERNAL' | 'UNKNOWN';
  readonly declared: boolean;
  readonly allowed: boolean;
  readonly arguments: string;
  readonly result: string | null;
  readonly status: string;
  readonly readOnly: boolean;
  readonly destructiveHint: boolean;
  readonly idempotentHint: boolean;
  readonly openWorldHint: boolean;
  readonly refreshTargets: readonly string[];
  readonly reviewReason: string | null;
  readonly diagnosticCode: string | null;
  readonly createdAt: string;
  readonly finishedAt: string | null;
}

export interface AiTokenUsage {
  readonly inputTokens: number;
  readonly cachedInputTokens: number;
  readonly cacheWriteInputTokens: number;
  readonly outputTokens: number;
  readonly reasoningOutputTokens: number;
  readonly totalTokens: number;
}

export interface AiRateLimitWindow {
  readonly key: string;
  readonly label: string;
  readonly usedPercent: number | null;
  readonly remainingPercent: number | null;
  readonly limit: number | null;
  readonly remaining: number | null;
  readonly windowMinutes: number | null;
  readonly resetsAt: string | null;
}

export interface AiQuotaSnapshot {
  readonly planType: string;
  readonly windows: readonly AiRateLimitWindow[];
  readonly observedAt: string | null;
}

export interface AiTurnUsage {
  readonly requestCount: number;
  readonly tokens: AiTokenUsage;
  readonly lastRequestTotalTokens: number;
  readonly modelContextWindow: number | null;
  readonly quota: AiQuotaSnapshot | null;
  readonly observedAt: string | null;
}

export interface AiUsageTotals {
  readonly requestCount: number;
  readonly tokens: AiTokenUsage;
}

export interface AiChatTurn {
  readonly id: number;
  readonly status: string;
  readonly model: string;
  readonly reasoningEffort: string;
  readonly reasoningUpgraded: boolean;
  readonly rejectionCount: number;
  readonly errorCode: string | null;
  readonly errorMessage: string | null;
  readonly createdAt: string;
  readonly finishedAt: string | null;
  readonly usage: AiTurnUsage;
  readonly tools: readonly AiToolInvocation[];
}

export interface AiConversationDetail {
  readonly id: number;
  readonly title: string;
  readonly mode: AiChatMode;
  readonly approvalPolicy: AiApprovalPolicy;
  readonly status: string;
  readonly messages: readonly AiChatMessage[];
  readonly turns: readonly AiChatTurn[];
  readonly usage: AiUsageTotals;
}

@Injectable({ providedIn: 'root' })
export class AiChatApiService {
  private readonly auth = inject(AuthService);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  conversations(): Promise<readonly AiConversationSummary[]> {
    return this.request('/api/ai/chat/conversations');
  }

  conversation(id: number): Promise<AiConversationDetail> {
    return this.request(`/api/ai/chat/conversations/${id}`);
  }

  submit(conversationId: number | null, message: string, mode: AiChatMode, approvalPolicy: AiApprovalPolicy, locale: AppLocale, reasoningEffort: AiReasoningEffort = 'low'): Promise<{ readonly conversationId: number; readonly turnId: number }> {
    return this.request('/api/ai/chat/turns', 'POST', { conversationId, message, mode, approvalPolicy, locale, reasoningEffort });
  }

  approve(id: number): Promise<void> {
    return this.request(`/api/ai/chat/tool-invocations/${id}/approve`, 'POST');
  }

  reject(id: number): Promise<void> {
    return this.request(`/api/ai/chat/tool-invocations/${id}/reject`, 'POST');
  }

  cancel(id: number): Promise<void> {
    return this.request(`/api/ai/chat/turns/${id}`, 'DELETE');
  }

  private async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const token = await this.auth.getAccessToken();
    const response = await fetch(`${this.apiBaseUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null) as { code?: string; message?: string } | null;
      throw new AiChatApiError(response.status, error?.code ?? null, error?.message ?? `Request failed with status ${response.status}.`);
    }
    if (response.status === 204) return undefined as T;
    return await response.json() as T;
  }
}
