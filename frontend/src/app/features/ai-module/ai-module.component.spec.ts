import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AiModuleApiService, AiSettings } from '../../core/api/ai-module-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AiModuleComponent } from './ai-module.component';

describe('AiModuleComponent conversation detail navigation', () => {
  let originalScrollIntoView: PropertyDescriptor | undefined;
  let scrollIntoView: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
    scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView });
  });

  afterEach(() => {
    if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView);
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
  });

  async function setup() {
    const summary = (id: number) => ({ id, title: `Conversation ${id}`, userSubject: 'test-subject', username: 'Test user',
      mode: 'READ_ONLY', approvalPolicy: 'ASK', status: 'COMPLETED', createdAt: '2026-10-18T10:00:00Z', updatedAt: '2026-10-18T10:00:00Z' });
    const api = {
      module: vi.fn().mockResolvedValue({ workerAvailable: true, sessionStatus: 'ACTIVE', settings: {} }),
      managedConversations: vi.fn().mockResolvedValue([summary(1), summary(2)]),
      managedConversation: vi.fn().mockImplementation(async (id: number) => ({ ...summary(id), messages: [], turns: [], usage: {} })),
    };
    await TestBed.configureTestingModule({
      imports: [AiModuleComponent], providers: [
        provideRouter([]),
        { provide: AiModuleApiService, useValue: api },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AiModuleComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    const tab = (key: string) => [...fixture.nativeElement.querySelectorAll('.tabs button')]
      .find((element: unknown) => (element as HTMLButtonElement).textContent?.trim() === key) as HTMLButtonElement;
    tab('ai.tabs.conversations').click();
    await fixture.whenStable();
    fixture.detectChanges();
    const viewButtons = () => fixture.nativeElement.querySelectorAll('.conversation-table__actions button:first-child') as NodeListOf<HTMLButtonElement>;
    return { fixture, api, tab, viewButtons };
  }

  it('opens the requested detail and brings it into view with keyboard focus after loading', async () => {
    const { fixture, api, viewButtons } = await setup();
    viewButtons()[1].click();
    await fixture.whenStable();
    fixture.detectChanges();
    const detail = fixture.nativeElement.querySelector('.conversation-detail') as HTMLElement;
    expect(api.managedConversation).toHaveBeenCalledWith(2);
    expect(detail.querySelector('h2')?.textContent).toBe('Conversation 2');
    expect(document.activeElement).toBe(detail);
    expect(detail.getAttribute('aria-labelledby')).toBe(detail.querySelector('h2')?.id);
    expect(scrollIntoView).toHaveBeenCalledOnce();
    expect(scrollIntoView.mock.contexts[0]).toBe(detail);
    expect(scrollIntoView).toHaveBeenCalledWith(expect.objectContaining({ block: 'start' }));
    fixture.destroy();
  });

  it('does not move focus or scroll when loading the detail fails', async () => {
    const { fixture, api, viewButtons } = await setup();
    api.managedConversation.mockRejectedValueOnce(new Error('Detail unavailable'));
    viewButtons()[0].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.notice--error')?.textContent).toContain('Detail unavailable');
    expect(fixture.nativeElement.querySelector('.conversation-detail')).toBeNull();
    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(viewButtons()[0].disabled).toBe(false);
    fixture.destroy();
  });

  it('does not scroll back to the conversation after the user switches tabs during loading', async () => {
    const { fixture, api, tab, viewButtons } = await setup();
    let resolve!: (value: unknown) => void;
    const response = new Promise(currentResolve => { resolve = currentResolve; });
    api.managedConversation.mockReturnValueOnce(response);
    viewButtons()[0].click();
    fixture.detectChanges();
    expect(viewButtons()[0].disabled).toBe(true);
    tab('ai.tabs.general').click();
    fixture.detectChanges();
    resolve({ id: 1, messages: [], turns: [], usage: {} });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.conversation-detail')).toBeNull();
    expect(scrollIntoView).not.toHaveBeenCalled();
    fixture.destroy();
  });
});

describe('AiModuleComponent conversation history setting', () => {
  it('loads and saves an explicit false without losing the rest of General settings', async () => {
    const settings: AiSettings = {
      enabled: true, provider: 'CODEX', model: 'configured-model', reasoningEffort: 'low',
      agentInstructions: 'existing instructions', refreshPolicy: 'ON_USE', reviewerModel: '',
      reviewerReasoningEffort: 'low', conversationHistorySearchEnabled: true, memoryEnabled: true,
    };
    const api = {
      module: vi.fn().mockResolvedValue({ workerAvailable: true, settings, sessionStatus: 'ACTIVE' }),
      update: vi.fn().mockImplementation(async (value: AiSettings) => value),
    };
    await TestBed.configureTestingModule({
      imports: [AiModuleComponent],
      providers: [
        provideRouter([]),
        { provide: AiModuleApiService, useValue: api },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AiModuleComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    const checkbox = fixture.nativeElement.querySelector('input[name="conversationHistorySearchEnabled"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
    checkbox.click();
    fixture.detectChanges();
    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(api.update).toHaveBeenCalledWith({ ...settings, conversationHistorySearchEnabled: false });
    fixture.detectChanges();
    expect(checkbox.checked).toBe(false);
    fixture.destroy();
  });
});

describe('AiModuleComponent memories', () => {
  const memory = (id: number, userSubject: string) => ({
    id, userSubject, key: `lesson.${id}`, version: 3, createdAt: '2026-10-17T10:00:00Z', updatedAt: '2026-10-17T11:00:00Z',
  });

  async function setup(workerAvailable = true) {
    const settings: AiSettings = {
      enabled: true, provider: 'CODEX', model: 'configured-model', reasoningEffort: 'low', agentInstructions: '',
      refreshPolicy: 'ON_USE', reviewerModel: '', reviewerReasoningEffort: 'low', conversationHistorySearchEnabled: true, memoryEnabled: false,
    };
    const api = {
      module: vi.fn().mockResolvedValue({ workerAvailable, settings, sessionStatus: 'ACTIVE' }),
      update: vi.fn().mockImplementation(async (value: AiSettings) => value),
      managedMemories: vi.fn().mockImplementation(async (page = 0) => ({ content: [memory(page + 1, page ? 'bob' : 'alice')], page, size: 20, totalElements: 21, totalPages: 2 })),
      managedMemory: vi.fn().mockResolvedValue({ ...memory(1, 'alice'), content: '<script>stored text</script>', sourceConversationId: null }),
      deleteManagedMemory: vi.fn().mockResolvedValue(undefined),
      deleteAllManagedMemories: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [AiModuleComponent], providers: [
        provideRouter([]),
        { provide: AiModuleApiService, useValue: api },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AiModuleComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    const button = (text: string) => [...fixture.nativeElement.querySelectorAll('button')].find((element: unknown) => (element as HTMLButtonElement).textContent?.trim() === text) as HTMLButtonElement;
    const render = async () => { await fixture.whenStable(); fixture.detectChanges(); await fixture.whenStable(); };
    return { fixture, api, button, render, settings };
  }

  it('preserves an explicit disabled value and saves it independently of history search', async () => {
    const { fixture, api, settings, render } = await setup();
    const checkbox = fixture.nativeElement.querySelector('[name="memoryEnabled"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
    checkbox.click();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await render();
    expect(api.update).toHaveBeenCalledWith({ ...settings, memoryEnabled: true });
    checkbox.click();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await render();
    expect(api.update).toHaveBeenLastCalledWith(settings);
    fixture.destroy();
  });

  it('manages all owners with memory and worker unavailable, and renders stored content as text', async () => {
    const { fixture, api, button, render } = await setup(false);
    button('ai.memories.title').click();
    await render();
    expect(api.managedMemories).toHaveBeenCalledWith(0);
    expect(fixture.nativeElement.textContent).toContain('alice');
    button('ai.conversations.view').click();
    await render();
    expect(fixture.nativeElement.querySelector('pre').textContent).toBe('<script>stored text</script>');
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    button('ai.memories.next').click();
    await render();
    expect(api.managedMemories).toHaveBeenLastCalledWith(1);
    expect(fixture.nativeElement.textContent).toContain('bob');
    expect(fixture.nativeElement.querySelector('pre')).toBeNull();
    fixture.destroy();
  });

  it('requires confirmation for single and global deletion, passes the version and reloads', async () => {
    const { fixture, api, button, render } = await setup();
    button('ai.memories.title').click();
    await render();
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false);
    button('ai.conversations.delete').click();
    await render();
    button('ai.conversations.deleteAll').click();
    await render();
    expect(api.deleteManagedMemory).not.toHaveBeenCalled();
    expect(api.deleteAllManagedMemories).not.toHaveBeenCalled();
    confirmation.mockReturnValue(true);
    button('ai.conversations.view').click();
    await render();
    button('ai.conversations.delete').click();
    await render();
    expect(api.deleteManagedMemory).toHaveBeenCalledWith(1, 3);
    expect(fixture.nativeElement.querySelector('pre')).toBeNull();
    api.managedMemories.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    button('ai.conversations.deleteAll').click();
    await render();
    expect(api.deleteAllManagedMemories).toHaveBeenCalledOnce();
    expect(api.managedMemories).toHaveBeenLastCalledWith(0);
    expect(button('ai.conversations.deleteAll').disabled).toBe(true);
    confirmation.mockRestore();
    fixture.destroy();
  });
});
