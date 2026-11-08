import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { EMPTY } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AiChatApiService,
  AiChatMessage,
  AiChatTurn,
  AiConversationDetail,
  AiToolInvocation,
} from '../../core/api/ai-chat-api.service';
import { AiModuleApiService, AiModuleState } from '../../core/api/ai-module-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { AiChatComponent, buildAiChatTimeline } from './ai-chat.component';

interface ComponentHarness {
  readonly selected: WritableSignal<AiConversationDetail | null>;
  readonly desiredConversationId: WritableSignal<number | null>;
  readonly reasoningEffort: () => string;
  readonly message: WritableSignal<string>;
  readonly mode: WritableSignal<'READ_ONLY' | 'PLAN' | 'DIRECT'>;
  readonly approvalPolicy: WritableSignal<'ASK' | 'AUTO'>;
  readonly open: WritableSignal<boolean>;
  readonly panelWidth: WritableSignal<number>;
  minimumPanelWidth(): number;
  startResize(event: PointerEvent): void;
  resizeWithKeyboard(event: KeyboardEvent): void;
  toggle(): Promise<void>;
  setMode(value: 'READ_ONLY' | 'PLAN' | 'DIRECT'): void;
  setApprovalPolicy(value: 'ASK' | 'AUTO'): void;
  selectNewDraft(): void;
  updateMessage(event: Event): void;
  setReasoningEffort(value: string): void;
  send(): Promise<void>;
  select(id: number, scrollMode?: 'force' | 'follow' | 'none'): Promise<void>;
  newConversation(): Promise<void>;
}

describe('AiChatComponent', () => {
  let api: { conversation: ReturnType<typeof vi.fn>; conversations: ReturnType<typeof vi.fn>; submit: ReturnType<typeof vi.fn> };
  let eventChannel: { on: ReturnType<typeof vi.fn>; start: ReturnType<typeof vi.fn> };
  let component: ComponentHarness;
  let moduleState: WritableSignal<AiModuleState>;
  let auth: { displayName: string; userIdentifier: string };
  const storageKey = 'alertify.aiChat.reasoningEffort.test-realm:subject-1';

  beforeEach(async () => {
    vi.restoreAllMocks();
    localStorage.clear();
    api = {
      conversation: vi.fn(),
      conversations: vi.fn().mockResolvedValue([]),
      submit: vi.fn().mockResolvedValue({ conversationId: 1, turnId: 7 }),
    };
    eventChannel = { on: vi.fn().mockReturnValue(EMPTY), start: vi.fn() };
    moduleState = signal(activeModuleState());
    auth = { displayName: 'Administrator', userIdentifier: 'test-realm:subject-1' };
    await TestBed.configureTestingModule({
      imports: [AiChatComponent],
      providers: [
        { provide: AiChatApiService, useValue: api },
        { provide: AiModuleApiService, useValue: { moduleState, module: vi.fn().mockImplementation(async () => moduleState()) } },
        { provide: AuthService, useValue: auth },
        { provide: LocalizationService, useValue: { locale: signal('en'), translateDynamic: (key: string) => key } },
        { provide: AdminEventChannelService, useValue: eventChannel },
      ],
    }).compileComponents();
    component = TestBed.createComponent(AiChatComponent).componentInstance as unknown as ComponentHarness;
  });

  it('keeps native disclosures open across refreshes and renders tool content as literal text', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    const invocation = { ...tool(1, 'RUNNING'), arguments: '<img src=x onerror="alert(1)">', result: 'x'.repeat(40000),
      approvalDecision: 'REJECTED', approvalActorType: 'AUTOMATIC_REVIEWER', approvalActor: 'review-model',
      approvalDecidedAt: '2026-10-11T10:00:01Z', reviewReason: 'Outside requested scope' };
    const detail = { ...conversation(1, 'ACTIVE'), turns: [{ ...turn(7, 'RUNNING'), tools: [invocation] }] };
    harness.selected.set(detail);
    fixture.detectChanges();
    const group = fixture.nativeElement.querySelector('.ai-chat__tools') as HTMLDetailsElement;
    const entry = group.querySelector('.ai-chat__tool') as HTMLDetailsElement;
    expect(group.open).toBe(false);
    expect(entry.open).toBe(false);
    expect(group.querySelector('summary .ai-chat__latest-tool code')?.textContent).toBe('test_tool_1');
    expect(group.querySelector('.ai-chat__latest-tool')?.textContent).toContain('RUNNING');
    group.querySelector('summary')!.click();
    entry.querySelector('summary')!.click();
    expect(group.open).toBe(true);
    expect(entry.open).toBe(true);
    expect(entry.querySelector('img')).toBeNull();
    expect(entry.textContent).toContain(invocation.arguments);
    expect(entry.textContent).toContain(invocation.result);
    expect(entry.textContent).toContain('review-model');
    expect(entry.textContent).toContain('Outside requested scope');
    expect(entry.textContent).toContain('REJECTED');
    harness.selected.set({ ...detail, turns: [{ ...turn(7, 'COMPLETED'), tools: [{ ...invocation, status: 'SUCCEEDED' }, tool(2, 'SUCCEEDED')] }] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ai-chat__tools')).toBe(group);
    expect(group.open).toBe(true);
    expect(entry.open).toBe(true);
    expect(group.querySelectorAll('.ai-chat__tool')).toHaveLength(2);
    expect(group.querySelector('summary .ai-chat__latest-tool code')?.textContent).toBe('test_tool_2');
    group.querySelector('summary')!.click();
    expect(group.open).toBe(false);
    fixture.destroy();
  });

  it('updates the latest tool and its status without opening a collapsed group', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    const first = tool(1, 'SUCCEEDED', '2026-10-11T10:00:01Z');
    const latest = tool(2, 'RUNNING', '2026-10-11T10:00:02Z');
    const detail = { ...conversation(1, 'ACTIVE'), turns: [{ ...turn(7, 'RUNNING'), tools: [latest, first] }] };
    harness.selected.set(detail);
    fixture.detectChanges();
    const group = fixture.nativeElement.querySelector('.ai-chat__tools') as HTMLDetailsElement;
    expect(group.open).toBe(false);
    expect(group.querySelector('summary .ai-chat__latest-tool code')?.textContent).toBe('test_tool_2');
    expect(group.querySelector('.ai-chat__latest-tool')?.textContent).toContain('RUNNING');
    harness.selected.set({ ...detail, turns: [{ ...turn(7, 'FAILED'), tools: [first, { ...latest, status: 'FAILED' }] }] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ai-chat__tools')).toBe(group);
    expect(group.open).toBe(false);
    expect(group.querySelector('.ai-chat__latest-tool')?.textContent).toContain('FAILED');
    harness.selected.set({ ...detail, turns: [{ ...turn(7, 'RUNNING'), tools: [first, latest, tool(3, 'RUNNING', '2026-10-11T10:00:03Z')] }] });
    fixture.detectChanges();
    expect(group.open).toBe(false);
    expect(group.querySelector('summary .ai-chat__latest-tool code')?.textContent).toBe('test_tool_3');
    fixture.destroy();
  });

  it('shows the latest tool of each separate group and keeps pending approval separate', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    harness.selected.set({ ...conversation(1, 'ACTIVE'),
      messages: [{ id: 1, turnId: 7, role: 'ASSISTANT', content: 'Between tools', createdAt: '2026-10-11T10:00:02Z' }],
      turns: [{ ...turn(7, 'WAITING_APPROVAL'), tools: [tool(1, 'SUCCEEDED', '2026-10-11T10:00:01Z'),
        tool(2, 'SUCCEEDED', '2026-10-11T10:00:03Z'), tool(3, 'PENDING_APPROVAL', '2026-10-11T10:00:04Z')] }],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(Array.from(root.querySelectorAll('.ai-chat__latest-tool code'), element => element.textContent)).toEqual(['test_tool_1', 'test_tool_2']);
    expect(root.querySelector('.ai-chat__approval code')?.textContent).toBe('test_tool_3');
    fixture.destroy();
  });

  it('keeps approval controls outside disclosures and moves rejected tools into history', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    const detail = { ...conversation(1, 'ACTIVE'), turns: [{ ...turn(7, 'WAITING_APPROVAL'), tools: [tool(1, 'PENDING_APPROVAL')] }] };
    harness.selected.set(detail);
    fixture.detectChanges();
    const approval = fixture.nativeElement.querySelector('.ai-chat__approval') as HTMLElement;
    expect(approval.closest('details')).toBeNull();
    expect(approval.querySelectorAll('button')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.ai-chat__tools')).toBeNull();
    harness.selected.set({ ...detail, turns: [{ ...turn(7, 'CANCELLED'), tools: [tool(1, 'REJECTED')] }] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ai-chat__approval')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.ai-chat__tool')).toHaveLength(1);
    fixture.destroy();
  });

  function setMaximum(maximum: string): void {
    moduleState.update((state) => ({ ...state, settings: { ...state.settings, reasoningEffort: maximum } }));
  }

  it.each([
    ['low', []],
    ['medium', ['low', 'medium']],
    ['high', ['low', 'medium', 'high']],
    ['xhigh', ['low', 'medium', 'high', 'xhigh']],
  ])('offers only levels up to %s and defaults to low', (maximum, expected) => {
    setMaximum(maximum);
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('#ai-chat-reasoning') as HTMLSelectElement | null;
    expect(select ? Array.from(select.options, (option) => option.value) : []).toEqual(expected);
    expect((fixture.componentInstance as unknown as ComponentHarness).reasoningEffort()).toBe('low');
    fixture.destroy();
  });

  it('stores a selection made through the selector and restores it for the same user', async () => {
    setMaximum('medium');
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const select = fixture.nativeElement.querySelector('#ai-chat-reasoning') as HTMLSelectElement;
    select.value = 'medium';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(localStorage.getItem(storageKey)).toBe('medium');
    fixture.destroy();

    const restored = TestBed.createComponent(AiChatComponent);
    expect((restored.componentInstance as unknown as ComponentHarness).reasoningEffort()).toBe('medium');
    auth.userIdentifier = 'test-realm:subject-2';
    const otherUser = TestBed.createComponent(AiChatComponent);
    expect((otherUser.componentInstance as unknown as ComponentHarness).reasoningEffort()).toBe('low');
    restored.destroy();
    otherUser.destroy();
  });

  it.each(['high', 'invalid', ''])('resets an invalid stored preference %s to low', (stored) => {
    setMaximum('medium');
    localStorage.setItem(storageKey, stored);
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.detectChanges();
    expect((fixture.componentInstance as unknown as ComponentHarness).reasoningEffort()).toBe('low');
    expect(localStorage.getItem(storageKey)).toBe('low');
    fixture.destroy();
  });

  it('waits for settings before validating a saved preference', () => {
    setMaximum('medium');
    const active = moduleState();
    (moduleState as WritableSignal<AiModuleState | null>).set(null);
    localStorage.setItem(storageKey, 'medium');
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.detectChanges();
    expect(localStorage.getItem(storageKey)).toBe('medium');
    moduleState.set(active);
    fixture.detectChanges();
    expect((fixture.componentInstance as unknown as ComponentHarness).reasoningEffort()).toBe('medium');
    fixture.destroy();
  });

  it('resets the preference and hides the selector when the system maximum is lowered', () => {
    setMaximum('medium');
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    harness.setReasoningEffort('medium');
    setMaximum('low');
    fixture.detectChanges();
    expect(harness.reasoningEffort()).toBe('low');
    expect(localStorage.getItem(storageKey)).toBe('low');
    expect(fixture.nativeElement.querySelector('#ai-chat-reasoning')).toBeNull();
    fixture.destroy();
  });

  it('synchronizes localStorage changes from another window and ignores other users', () => {
    setMaximum('medium');
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.detectChanges();
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    window.dispatchEvent(new StorageEvent('storage', { key: storageKey, newValue: 'medium', storageArea: localStorage }));
    fixture.detectChanges();
    expect(harness.reasoningEffort()).toBe('medium');
    window.dispatchEvent(new StorageEvent('storage', { key: `${storageKey}-other`, newValue: 'low', storageArea: localStorage }));
    expect(harness.reasoningEffort()).toBe('medium');
    window.dispatchEvent(new StorageEvent('storage', { key: storageKey, newValue: 'high', storageArea: localStorage }));
    fixture.detectChanges();
    expect(harness.reasoningEffort()).toBe('low');
    expect(localStorage.getItem(storageKey)).toBe('low');
    fixture.destroy();
  });

  it('keeps the selection in memory when localStorage is unavailable', () => {
    setMaximum('medium');
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Unavailable'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Unavailable'); });
    const fixture = TestBed.createComponent(AiChatComponent);
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    harness.setReasoningEffort('medium');
    fixture.detectChanges();
    expect(harness.reasoningEffort()).toBe('medium');
    fixture.destroy();
  });

  it('sends the selected effort and preserves the preference across conversations and model upgrades', async () => {
    setMaximum('medium');
    const fixture = TestBed.createComponent(AiChatComponent);
    const harness = fixture.componentInstance as unknown as ComponentHarness;
    fixture.detectChanges();
    await fixture.whenStable();
    harness.setReasoningEffort('medium');
    harness.message.set('hello');
    api.conversation.mockResolvedValue(conversation(1, 'COMPLETED'));
    await harness.send();
    expect(api.submit).toHaveBeenCalledWith(null, 'hello', 'READ_ONLY', 'ASK', 'en', 'medium');
    await harness.newConversation();
    expect(harness.reasoningEffort()).toBe('medium');
    harness.setReasoningEffort('low');
    harness.selected.set({ ...conversation(1, 'COMPLETED'), turns: [{ ...turn(7, 'COMPLETED'), reasoningEffort: 'medium', reasoningUpgraded: true }] });
    fixture.detectChanges();
    expect(harness.reasoningEffort()).toBe('low');
    expect(localStorage.getItem(storageKey)).toBe('low');
    fixture.destroy();
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

  it('restores each conversation draft and the new-chat draft with its local options', async () => {
    api.conversation.mockImplementation(async (id: number) => conversation(id, 'COMPLETED'));
    await component.newConversation();
    component.message.set('new draft\nsecond line');
    component.setMode('PLAN');
    await component.select(1, 'none');
    component.message.set('first draft');
    component.setMode('DIRECT');
    component.setApprovalPolicy('AUTO');
    await component.select(2, 'none');
    expect(component.message()).toBe('');
    await component.select(1, 'none');
    expect(component.message()).toBe('first draft');
    expect(component.mode()).toBe('DIRECT');
    expect(component.approvalPolicy()).toBe('AUTO');
    component.selectNewDraft();
    expect(component.message()).toBe('new draft\nsecond line');
    expect(component.mode()).toBe('PLAN');
    await component.newConversation();
    expect(component.message()).toBe('');
  });

  it('does not overwrite local options and text when refreshing conversation details', async () => {
    api.conversation.mockResolvedValue(conversation(1, 'COMPLETED'));
    await component.select(1, 'none');
    component.message.set('unsent');
    component.setMode('DIRECT');
    component.setApprovalPolicy('AUTO');
    await component.select(1, 'none');
    expect(component.message()).toBe('unsent');
    expect(component.mode()).toBe('DIRECT');
    expect(component.approvalPolicy()).toBe('AUTO');
  });

  it('does not clear another conversation draft when a pending submit finishes', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    const chat = fixture.componentInstance as unknown as ComponentHarness;
    fixture.detectChanges();
    await fixture.whenStable();
    api.conversation.mockImplementation(async (id: number) => conversation(id, 'COMPLETED'));
    await chat.select(1, 'none');
    chat.message.set('submitted from one');
    const pending = deferred<{ conversationId: number; turnId: number }>();
    api.submit.mockReturnValueOnce(pending.promise);
    const sending = chat.send();
    await chat.select(2, 'none');
    chat.message.set('draft in two');
    pending.resolve({ conversationId: 1, turnId: 7 });
    await sending;
    expect(chat.desiredConversationId()).toBe(2);
    expect(chat.message()).toBe('draft in two');
    await chat.select(1, 'none');
    expect(chat.message()).toBe('');
    fixture.destroy();
  });

  it('migrates text written while sending a new chat without losing it', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    const chat = fixture.componentInstance as unknown as ComponentHarness;
    fixture.detectChanges();
    await fixture.whenStable();
    const pending = deferred<{ conversationId: number; turnId: number }>();
    api.submit.mockReturnValueOnce(pending.promise);
    api.conversation.mockResolvedValue(conversation(1, 'COMPLETED'));
    chat.message.set('submitted');
    const sending = chat.send();
    chat.updateMessage({ target: { value: 'written afterwards' } } as unknown as Event);
    pending.resolve({ conversationId: 1, turnId: 7 });
    await sending;
    expect(chat.desiredConversationId()).toBe(1);
    expect(chat.message()).toBe('written afterwards');
    fixture.destroy();
  });

  function pointer(type: string, clientX: number, pointerId = 1, buttons = 1): PointerEvent {
    return new PointerEvent(type, { clientX, pointerId, button: 0, buttons, pointerType: 'mouse' });
  }

  async function openPanel() {
    const fixture = TestBed.createComponent(AiChatComponent);
    const chat = fixture.componentInstance as unknown as ComponentHarness;
    fixture.detectChanges();
    await fixture.whenStable();
    await chat.toggle();
    return { fixture, chat };
  }

  it.each([6, 5, 4, 0])('only closes during a resize when the pointer is within 5px of the right edge (%s px)', async distance => {
    const { fixture, chat } = await openPanel();
    chat.updateMessage({ target: { value: 'resize draft\nsecond line' } } as unknown as Event);
    chat.setMode('DIRECT');
    chat.setApprovalPolicy('AUTO');
    const rightEdge = window.innerWidth;
    chat.startResize(pointer('pointerdown', rightEdge - 480));
    window.dispatchEvent(pointer('pointermove', rightEdge - chat.minimumPanelWidth()));
    expect(chat.panelWidth()).toBe(chat.minimumPanelWidth());
    expect(chat.open()).toBe(true);
    window.dispatchEvent(pointer('pointermove', rightEdge - distance));
    expect(chat.open()).toBe(distance > 5);
    if (distance <= 5) {
      expect(localStorage.getItem('alertify.aiChat.panelWidth')).toBe(String(chat.minimumPanelWidth()));
      expect(document.body.style.cursor).not.toBe('col-resize');
      expect(document.body.style.userSelect).not.toBe('none');
      window.dispatchEvent(pointer('pointermove', rightEdge - 600));
      expect(chat.panelWidth()).toBe(chat.minimumPanelWidth());
      await chat.toggle();
      expect(chat.open()).toBe(true);
      expect(chat.panelWidth()).toBe(chat.minimumPanelWidth());
      expect(chat.message()).toBe('resize draft\nsecond line');
      expect(chat.mode()).toBe('DIRECT');
      expect(chat.approvalPolicy()).toBe('AUTO');
    }
    window.dispatchEvent(pointer('pointerup', rightEdge - distance, 1, 0));
    fixture.destroy();
  });

  it.each(['pointerup', 'pointercancel'])('ends resizing on %s without closing or responding to later movement', async type => {
    const { fixture, chat } = await openPanel();
    chat.startResize(pointer('pointerdown', window.innerWidth - 480));
    window.dispatchEvent(pointer('pointermove', window.innerWidth - 320));
    window.dispatchEvent(pointer(type, window.innerWidth - 320, 1, 0));
    window.dispatchEvent(pointer('pointermove', window.innerWidth));
    expect(chat.open()).toBe(true);
    expect(chat.panelWidth()).toBe(chat.minimumPanelWidth());
    fixture.destroy();
  });

  it('uses the visible page edge, ignores other pointers, and restores prior body styles', async () => {
    const { fixture, chat } = await openPanel();
    const oldCursor = document.body.style.cursor;
    const oldSelect = document.body.style.userSelect;
    document.body.style.cursor = 'crosshair';
    document.body.style.userSelect = 'text';
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(window.innerWidth - 17);
    chat.startResize(pointer('pointerdown', window.innerWidth - 480));
    window.dispatchEvent(pointer('pointermove', window.innerWidth, 2));
    expect(chat.open()).toBe(true);
    window.dispatchEvent(pointer('pointermove', document.documentElement.clientWidth - 5));
    expect(chat.open()).toBe(false);
    expect(document.body.style.cursor).toBe('crosshair');
    expect(document.body.style.userSelect).toBe('text');
    document.body.style.cursor = oldCursor;
    document.body.style.userSelect = oldSelect;
    fixture.destroy();
  });

  it('does not close for released mouse movement, keyboard shrinking or browser resizing', async () => {
    const { fixture, chat } = await openPanel();
    chat.startResize(pointer('pointerdown', window.innerWidth - 480));
    window.dispatchEvent(pointer('pointermove', window.innerWidth, 1, 0));
    expect(chat.open()).toBe(true);
    for (let index = 0; index < 30; index++) chat.resizeWithKeyboard(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    window.dispatchEvent(new Event('resize'));
    expect(chat.panelWidth()).toBe(chat.minimumPanelWidth());
    expect(chat.open()).toBe(true);
    fixture.destroy();
  });

  it('does not activate the resize-close gesture in the floating window', async () => {
    const fixture = TestBed.createComponent(AiChatComponent);
    fixture.componentRef.setInput('popout', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const chat = fixture.componentInstance as unknown as ComponentHarness;
    chat.startResize(pointer('pointerdown', window.innerWidth - 480));
    window.dispatchEvent(pointer('pointermove', window.innerWidth));
    expect(chat.open()).toBe(true);
    fixture.destroy();
  });
});

describe('buildAiChatTimeline', () => {
  it('interleaves precise timestamps and groups only adjacent tools from the same turn', () => {
    const messages: AiChatMessage[] = [
      { id: 1, turnId: 7, role: 'USER', content: 'request', createdAt: '2026-10-11T10:00:00Z' },
      { id: 2, turnId: 7, role: 'ASSISTANT', content: 'progress', createdAt: '2026-10-11T10:00:01.000200Z' },
    ];
    const first = { ...turn(7, 'FAILED'), tools: [
      tool(1, 'SUCCEEDED', '2026-10-11T10:00:01.000100Z'),
      tool(2, 'FAILED', '2026-10-11T10:00:01.000150Z'),
      tool(3, 'REJECTED', '2026-10-11T10:00:01.000300Z'),
    ] };
    const next = { ...turn(8, 'COMPLETED'), tools: [tool(4, 'SUCCEEDED', '2026-10-11T10:00:03Z')] };
    const timeline = buildAiChatTimeline(messages, [first, next]);
    expect(timeline.map((item) => item.kind)).toEqual(['message', 'tools', 'message', 'tools', 'failure', 'tools']);
    expect(timeline[1]).toMatchObject({ key: 'tools-1', tools: [{ id: 1 }, { id: 2 }] });
    expect(timeline[3]).toMatchObject({ tools: [{ id: 3 }] });
  });

  it('shows pending approvals once, then merges resolved invocations into their sequence', () => {
    const tools = [tool(1, 'SUCCEEDED'), tool(2, 'PENDING_APPROVAL'), tool(3, 'RUNNING')];
    const timeline = buildAiChatTimeline([], [{ ...turn(7, 'WAITING_APPROVAL'), tools }]);
    expect(timeline.map((item) => item.kind)).toEqual(['tools', 'approval', 'tools']);
    expect(timeline[1]).toMatchObject({ tool: { id: 2 } });
    const resolved = buildAiChatTimeline([], [{ ...turn(7, 'COMPLETED'), tools: tools.map((item) => ({ ...item, status: 'SUCCEEDED' })) }]);
    expect(resolved).toHaveLength(1);
    expect(resolved[0]).toMatchObject({ key: 'tools-1', tools: [{ id: 1 }, { id: 2 }, { id: 3 }] });
  });

  it('orders identical timestamps deterministically without merging turns', () => {
    const message: AiChatMessage = { id: 1, turnId: 7, role: 'USER', content: 'request', createdAt: '2026-10-11T10:00:01Z' };
    const timeline = buildAiChatTimeline([message], [
      { ...turn(7, 'COMPLETED'), tools: [tool(2, 'CANCELLED'), tool(1, 'INTERRUPTED')] },
      { ...turn(8, 'COMPLETED'), tools: [tool(3, 'REJECTED')] },
    ]);
    expect(timeline.map((item) => item.key)).toEqual(['message-1', 'tools-1', 'tools-3']);
    expect(timeline[1]).toMatchObject({ tools: [{ id: 1 }, { id: 2 }] });
  });

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

function tool(id: number, status: string, createdAt = '2026-10-11T10:00:01Z'): AiToolInvocation {
  return {
    id, callId: `call-${id}`, generation: 1, toolName: `test_tool_${id}`, kind: 'DOMAIN', declared: true, allowed: true,
    arguments: '{"id":1}', result: '{"ok":true}', status, readOnly: true, destructiveHint: false,
    idempotentHint: true, openWorldHint: false, refreshTargets: [], reviewReason: null, diagnosticCode: null,
    createdAt, finishedAt: null,
  };
}

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
      conversationHistorySearchEnabled: true, memoryEnabled: true,
    },
  };
}
