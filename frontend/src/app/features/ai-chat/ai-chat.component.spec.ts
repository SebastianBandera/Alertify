import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { EMPTY } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AiChatApiService,
  AiChatMessage,
  AiChatTurn,
  AiConversationDetail,
} from '../../core/api/ai-chat-api.service';
import { AiModuleApiService, AiModuleState } from '../../core/api/ai-module-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { AiChatComponent, buildAiChatTimeline } from './ai-chat.component';

interface ComponentHarness {
  readonly selected: WritableSignal<AiConversationDetail | null>;
  readonly desiredConversationId: WritableSignal<number | null>;
  select(id: number, scrollMode?: 'force' | 'follow' | 'none'): Promise<void>;
  newConversation(): Promise<void>;
}

describe('AiChatComponent', () => {
  let api: { conversation: ReturnType<typeof vi.fn>; conversations: ReturnType<typeof vi.fn> };
  let eventChannel: { on: ReturnType<typeof vi.fn>; start: ReturnType<typeof vi.fn> };
  let component: ComponentHarness;

  beforeEach(async () => {
    api = {
      conversation: vi.fn(),
      conversations: vi.fn().mockResolvedValue([]),
    };
    eventChannel = { on: vi.fn().mockReturnValue(EMPTY), start: vi.fn() };
    const moduleState = signal(activeModuleState());
    await TestBed.configureTestingModule({
      imports: [AiChatComponent],
      providers: [
        { provide: AiChatApiService, useValue: api },
        { provide: AiModuleApiService, useValue: { moduleState, module: vi.fn().mockResolvedValue(moduleState()) } },
        { provide: AuthService, useValue: { displayName: 'Administrator' } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translateDynamic: (key: string) => key } },
        { provide: AdminEventChannelService, useValue: eventChannel },
      ],
    }).compileComponents();
    component = TestBed.createComponent(AiChatComponent).componentInstance as unknown as ComponentHarness;
  });

  it('starts the administrative event channel when rendered outside the application shell', () => {
    eventChannel.start.mockClear();
    const fixture = TestBed.createComponent(AiChatComponent);

    fixture.detectChanges();

    expect(eventChannel.start).toHaveBeenCalled();
    fixture.destroy();
  });

  it('renders a safe table and starts the response without template whitespace', () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    harness.selected.set({
      ...conversation(1, 'COMPLETED'),
      messages: [{ id: 1, turnId: null, role: 'ASSISTANT', content: '  Sí, **máximo**.\n\n| Nodo | Uso CPU |\n|---|---:|\n| `worker-codex` | <img src=x> |', createdAt: '2026-10-12T10:00:00Z' }],
    });

    fixture.detectChanges();

    const article = fixture.nativeElement.querySelector('.ai-chat__message') as HTMLElement;
    expect(article.querySelector('p')?.textContent).toBe('Sí, máximo.');
    expect(article.querySelector('strong')?.textContent).toBe('máximo');
    expect(article.querySelectorAll('th')).toHaveLength(2);
    expect(article.querySelector('td code')?.textContent).toBe('worker-codex');
    expect(article.querySelectorAll('td')[1].classList.contains('ai-chat__align-right')).toBe(true);
    expect(article.querySelectorAll('td')[1].textContent).toBe('<img src=x>');
    expect(article.querySelector('img')).toBeNull();
    fixture.destroy();
  });

  it('renders nested formatting and quotes through Angular sanitization', () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    harness.selected.set({
      ...conversation(1, 'COMPLETED'),
      messages: [{ id: 1, turnId: null, role: 'ASSISTANT', content: '- *Cursiva*\n- ***Ambas***\n- ~~Tachado~~\n\n> Cita\n\n[OpenAI](https://openai.com/)\n\n<script>alert(1)</script>', createdAt: '2026-10-12T10:00:00Z' }],
    });

    fixture.detectChanges();

    const article = fixture.nativeElement.querySelector('.ai-chat__message') as HTMLElement;
    expect(article.querySelector('li em')?.textContent).toBe('Cursiva');
    expect(article.querySelector('em strong, strong em')?.textContent).toBe('Ambas');
    expect(article.querySelector('s')?.textContent).toBe('Tachado');
    expect(article.querySelector('blockquote')?.textContent?.trim()).toBe('Cita');
    expect(article.querySelector('script')).toBeNull();
    expect(article.querySelector('a')).toBeNull();
    expect(article.textContent).toContain('https://openai.com/');
    fixture.destroy();
  });

  it('preserves the allowed underline, superscript and subscript tags through Angular sanitization', () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    harness.selected.set({
      ...conversation(1, 'COMPLETED'),
      messages: [{ id: 1, turnId: null, role: 'ASSISTANT', content: '<u>subrayado</u> x<sup>2</sup> H<sub>2</sub>O\n\n<u><img src=x onerror=alert(1)></u>', createdAt: '2026-10-12T10:00:00Z' }],
    });

    fixture.detectChanges();

    const article = fixture.nativeElement.querySelector('.ai-chat__message') as HTMLElement;
    expect(article.querySelector('u')?.textContent).toBe('subrayado');
    expect(article.querySelector('sup')?.textContent).toBe('2');
    expect(article.querySelector('sub')?.textContent).toBe('2');
    expect(article.querySelector('img, a, script')).toBeNull();
    fixture.destroy();
  });

  it('discards an older detail response that arrives after the latest selection', async () => {
    const first = deferred<AiConversationDetail>();
    const second = deferred<AiConversationDetail>();
    api.conversation.mockImplementation((id: number) => id === 1 ? first.promise : second.promise);

    const firstSelection = component.select(1, 'none');
    const secondSelection = component.select(2, 'none');
    second.resolve(conversation(2, 'COMPLETED'));
    await secondSelection;
    first.resolve(conversation(1, 'RUNNING'));
    await firstSelection;

    expect(component.desiredConversationId()).toBe(2);
    expect(component.selected()?.id).toBe(2);
    expect(component.selected()?.status).toBe('COMPLETED');
  });

  it('invalidates a pending detail response when the user starts a new conversation', async () => {
    const pending = deferred<AiConversationDetail>();
    api.conversation.mockReturnValue(pending.promise);

    const selection = component.select(1, 'none');
    await component.newConversation();
    pending.resolve(conversation(1, 'RUNNING'));
    await selection;

    expect(component.desiredConversationId()).toBeNull();
    expect(component.selected()).toBeNull();
  });
});

describe('buildAiChatTimeline', () => {
  it('places a persisted failure after the last message from its turn', () => {
    const messages: readonly AiChatMessage[] = [
      { id: 1, turnId: 7, role: 'USER', content: 'request', createdAt: '2026-10-11T10:00:00Z' },
      { id: 2, turnId: 7, role: 'ASSISTANT', content: 'partial response', createdAt: '2026-10-11T10:00:01Z' },
    ];
    const failed = turn(7, 'FAILED');

    const timeline = buildAiChatTimeline(messages, [failed]);

    expect(timeline.map((item) => item.kind)).toEqual(['message', 'message', 'failure']);
    expect(timeline[2]).toMatchObject({ kind: 'failure', turn: failed });
  });

  it('retains failures that have no persisted messages', () => {
    const failed = turn(8, 'FAILED');

    expect(buildAiChatTimeline([], [failed])).toEqual([
      { kind: 'failure', key: 'failure-8', turn: failed },
    ]);
  });
});

function deferred<T>(): { readonly promise: Promise<T>; resolve(value: T): void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((currentResolve) => resolve = currentResolve);
  return { promise, resolve };
}

function conversation(id: number, status: string): AiConversationDetail {
  return {
    id,
    title: `Conversation ${id}`,
    mode: 'READ_ONLY',
    approvalPolicy: 'ASK',
    status,
    messages: [],
    turns: [],
    usage: { requestCount: 0, tokens: tokens() },
  };
}

function turn(id: number, status: string): AiChatTurn {
  return {
    id,
    status,
    model: 'gpt-test',
    reasoningEffort: 'low',
    reasoningUpgraded: false,
    rejectionCount: 0,
    errorCode: status === 'FAILED' ? 'AI_PROVIDER_FAILED' : null,
    errorMessage: status === 'FAILED' ? 'Provider failed' : null,
    createdAt: '2026-10-11T10:00:00Z',
    finishedAt: '2026-10-11T10:00:02Z',
    usage: {
      requestCount: 0,
      tokens: tokens(),
      lastRequestTotalTokens: 0,
      modelContextWindow: null,
      quota: null,
      observedAt: null,
    },
    tools: [],
  };
}

function tokens() {
  return {
    inputTokens: 0,
    cachedInputTokens: 0,
    cacheWriteInputTokens: 0,
    outputTokens: 0,
    reasoningOutputTokens: 0,
    totalTokens: 0,
  };
}

function activeModuleState(): AiModuleState {
  return {
    workerAvailable: true,
    workerErrorCode: '',
    enabled: true,
    authMode: 'BROWSER',
    sessionStatus: 'ACTIVE',
    accountLabel: 'Administrator',
    expiresAt: '',
    refreshExpiresAt: '',
    lastRefreshedAt: '',
    refreshPolicy: 'ON_USE',
    loginPending: false,
    pendingLoginMode: '',
    extensionInstanceId: '',
    extensionProtocolVersion: 1,
    settings: {
      enabled: true,
      provider: 'CODEX',
      model: 'gpt-test',
      reasoningEffort: 'low',
      agentInstructions: '',
      refreshPolicy: 'ON_USE',
      reviewerModel: 'gpt-test',
      reviewerReasoningEffort: 'low',
    },
  };
}
