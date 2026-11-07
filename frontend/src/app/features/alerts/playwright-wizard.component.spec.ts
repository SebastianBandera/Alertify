import { Component, WritableSignal, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EMPTY } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AlertApiService } from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { PlaywrightRemoteViewComponent } from './playwright-remote-view.component';
import { PlaywrightWizardApiService, WizardSettings, WizardState, normalizeWizardUrl } from './playwright-wizard-api.service';
import { PlaywrightWizardComponent } from './playwright-wizard.component';

@Component({ selector: 'app-playwright-remote-view', template: '' })
class RemoteViewStub {
  readonly draftId = input('');
  readonly disabled = input(false);
  readonly elementSelected = output<unknown>();
  readonly scrolled = output<number>();
}

interface WizardHarness {
  readonly state: WritableSignal<WizardState | null>;
  readonly settings: WritableSignal<WizardSettings>;
  readonly message: WritableSignal<string>;
  send(): Promise<void>;
  selectedReference: WritableSignal<string>;
  credentialField: WritableSignal<string>;
  insertReference(): void;
  update(next: WizardState): void;
  command(operation: string): Promise<void>;
  save(): Promise<void>;
  patch(value: Partial<WizardSettings>): void;
  readonly saving: WritableSignal<boolean>;
  readonly error: WritableSignal<string>;
  goToLine(line: number): void;
}

describe('PlaywrightWizardComponent', () => {
  let fixture: ComponentFixture<PlaywrightWizardComponent>;
  let harness: WizardHarness;
  let current: WizardState;
  let request: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    current = {
      id: 'draft-1',
      document: { settings: { url: 'https://example.com/', steps: '', engine: 'chromium', browsers: ['chromium'], loadTimeoutSeconds: 10, elementTimeoutSeconds: 5, mode: 'ASK' }, messages: [], proposal: 'QUERY #menu\nHOVER' },
      open: true, busy: false, error: '', idleRemainingSeconds: 300, attachmentReady: false,
      selection: null, progress: null, observation: null, tokens: 0, modelRequests: 0,
    };
    request = vi.fn(async (operation: string, payload?: Record<string, unknown>) => {
      if (operation === 'LIST') return [];
      if (operation === 'SAVE') current.document.settings = structuredClone(payload!['settings'] as WizardSettings);
      if (operation === 'SET_MASK') current = { ...current, maskEnabled: payload!['enabled'] as boolean, attachmentReady: false };
      if (operation === 'APPROVE' || operation === 'REJECT') {
        const next = structuredClone(current);
        if (operation === 'APPROVE') next.document.settings.steps = next.document.proposal!;
        delete next.document.proposal;
        current = next;
      }
      return structuredClone(current);
    });
    await TestBed.configureTestingModule({
      imports: [PlaywrightWizardComponent],
      providers: [
        { provide: PlaywrightWizardApiService, useValue: { request } },
        { provide: AlertApiService, useValue: { bindingOptions: vi.fn().mockResolvedValue({ configurations: [{ name: 'Usuario', valueType: 'STRING' }], secrets: [{ name: 'Contraseña_ANEP', valueType: 'STRING' }, { name: 'Login temporal', valueType: 'USERNAME_PASSWORD' }, { name: 'Archivo', valueType: 'BINARY' }] }) } },
        { provide: LocalizationService, useValue: { locale: signal('es-UY'), translateDynamic: (key: string) => key.replace('alerts.wizard.', '') } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
      ],
    }).overrideComponent(PlaywrightWizardComponent, { remove: { imports: [PlaywrightRemoteViewComponent] }, add: { imports: [RemoteViewStub] } }).compileComponents();
    fixture = TestBed.createComponent(PlaywrightWizardComponent);
    harness = fixture.componentInstance as unknown as WizardHarness;
    fixture.detectChanges();
    await fixture.whenStable();
    harness.state.set(structuredClone(current));
    harness.settings.set(structuredClone(current.document.settings));
    fixture.detectChanges();
  });

  function button(label: string): HTMLButtonElement {
    return [...fixture.nativeElement.querySelectorAll('button')].find((element: HTMLButtonElement) => element.textContent?.trim() === label)!;
  }

  it('changes the view mask only after a manual checkbox action without editing the script', async () => {
    request.mockClear();
    const settings = structuredClone(harness.settings());
    const checkbox = fixture.nativeElement.querySelector('.mask-control input') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
    expect(request).not.toHaveBeenCalled();
    checkbox.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(request).toHaveBeenCalledExactlyOnceWith('SET_MASK', expect.objectContaining({ id: current.id, enabled: false }));
    expect(checkbox.checked).toBe(false);
    expect(harness.settings()).toEqual(settings);
    request.mockClear();
    checkbox.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(request).toHaveBeenCalledExactlyOnceWith('SET_MASK', expect.objectContaining({ enabled: true }));
    expect(checkbox.checked).toBe(true);
    fixture.destroy();
  });

  it('blocks attachments while the view is unmasked and locks its control during operations', () => {
    delete current.document.proposal;
    harness.update({ ...structuredClone(current), maskEnabled: false });
    fixture.detectChanges();
    expect(button('attach').disabled).toBe(true);
    harness.update({ ...structuredClone(current), maskEnabled: true });
    fixture.detectChanges();
    expect(button('attach').disabled).toBe(false);
    harness.update({ ...structuredClone(current), busy: true });
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.mask-control input') as HTMLInputElement).disabled).toBe(true);
    fixture.destroy();
  });

  it('numbers physical script lines including comments, empty lines and the trailing line without altering the script', async () => {
    const script = '# comment\r\n\r\nWAIT_VISIBLE h1 10s\r\nQUERY h1\r\n';
    harness.settings.set({ ...current.document.settings, steps: script });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.line-numbers pre')?.textContent).toBe('1\n2\n3\n4\n5');
    const editor = fixture.nativeElement.querySelector('.code-editor textarea') as HTMLTextAreaElement;
    expect(editor.getAttribute('wrap')).toBe('off');
    expect(harness.settings().steps).toBe(script);
    expect(fixture.nativeElement.querySelector('.line-numbers')?.getAttribute('aria-hidden')).toBe('true');
    fixture.destroy();
  });

  it('keeps line numbers synchronized with vertical editor scrolling', () => {
    harness.settings.set({ ...current.document.settings, steps: Array.from({ length: 30 }, () => 'QUERY h1').join('\n') });
    fixture.detectChanges();
    const editor = fixture.nativeElement.querySelector('.code-editor textarea') as HTMLTextAreaElement;
    editor.scrollTop = 240;
    editor.dispatchEvent(new Event('scroll'));
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.line-numbers-content') as HTMLElement).style.transform).toBe('translateY(-240px)');
    fixture.destroy();
  });

  it('marks the failed physical line and selects it when the user clicks its receipt button', async () => {
    current.document.settings.steps = Array.from({ length: 25 }, (_, index) => 'QUERY #line' + (index + 1)).join('\n');
    current.document.lastAction = { operation: 'EXECUTE', status: 'FAILED', at: '2026-10-31T15:00:00Z', result: { completed: 19, failedLine: 20 } };
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    const marker = fixture.nativeElement.querySelector('.failed-line-number') as HTMLElement;
    expect(marker.textContent?.trim()).toBe('20');
    expect(marker.style.top).toBe('388px');
    button('line 20').click();
    fixture.detectChanges();
    const editor = fixture.nativeElement.querySelector('.code-editor textarea') as HTMLTextAreaElement;
    expect(document.activeElement).toBe(editor);
    expect(editor.value.slice(editor.selectionStart, editor.selectionEnd)).toBe('QUERY #line20');
    expect(editor.scrollTop).toBeGreaterThan(0);
    expect(current.document.settings.steps).toBe(harness.settings().steps);
    fixture.destroy();
  });

  it('hides a stale or invalid failed-line marker and respects execution locking', async () => {
    current.document.settings.steps = 'QUERY #one\nQUERY #two';
    current.document.lastAction = { operation: 'EXECUTE', status: 'FAILED', at: '2026-10-31T15:00:00Z', result: { failedLine: 2 } };
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    const editor = fixture.nativeElement.querySelector('.code-editor textarea') as HTMLTextAreaElement;
    editor.setSelectionRange(0, 0);
    harness.update({ ...structuredClone(current), busy: true });
    fixture.detectChanges();
    expect(button('line 2').disabled).toBe(true);
    harness.goToLine(2);
    expect(editor.selectionStart).toBe(0);
    harness.update(structuredClone(current));
    harness.patch({ steps: '# added line\nQUERY #one\nQUERY #two' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.failed-line-number')).toBeNull();
    harness.settings.set(structuredClone(current.document.settings));
    current.document.lastAction.result!.failedLine = 99;
    harness.update(structuredClone(current));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.failed-line-number')).toBeNull();
    fixture.destroy();
  });

  it('shows activity immediately and keeps it visible until the backend finishes', async () => {
    let finish!: (value: WizardState) => void;
    request.mockImplementationOnce(() => new Promise<WizardState>(resolve => { finish = resolve; }));
    const command = harness.command('OBSERVE');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.messages .chat-working[role="status"]')?.textContent).toContain('working');
    const running = { ...structuredClone(current), busy: true };
    await vi.waitFor(() => expect(finish).toBeDefined());
    finish(running);
    await command;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.messages .chat-working')).not.toBeNull();
    harness.update({ ...running, busy: false });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.messages .chat-working')).toBeNull();
    fixture.destroy();
  });

  it('clears chat activity when a request fails', async () => {
    request.mockRejectedValueOnce(new Error('Synthetic operation failure'));
    await harness.command('OBSERVE');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.messages .chat-working')).toBeNull();
    expect(harness.error()).toBe('Synthetic operation failure');
    fixture.destroy();
  });

  it('scrolls the conversation after rendering new or updated replies without moving focus', async () => {
    const viewport = fixture.nativeElement.querySelector('.messages') as HTMLElement;
    let height = 800;
    Object.defineProperty(viewport, 'scrollHeight', { get: () => height });
    const editor = fixture.nativeElement.querySelector('.steps-panel textarea.code') as HTMLTextAreaElement;
    editor.focus();
    current.document.messages = [{ role: 'ASSISTANT', content: 'First reply', hasImage: false, at: '2026-10-31T15:00:00Z' }];
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(viewport.scrollTop).toBe(800);
    expect(viewport.textContent).toContain('First reply');
    viewport.scrollTop = 20;
    height = 950;
    current.document.messages[0].content = 'First reply with additional content';
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(viewport.scrollTop).toBe(950);
    expect(document.activeElement).toBe(editor);
    fixture.destroy();
  });

  it('reveals the working indicator and preserves manual scroll on unchanged or system-only updates', async () => {
    const viewport = fixture.nativeElement.querySelector('.messages') as HTMLElement;
    Object.defineProperty(viewport, 'scrollHeight', { get: () => 1000 });
    current.busy = true;
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(viewport.scrollTop).toBe(1000);
    viewport.scrollTop = 30;
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(viewport.scrollTop).toBe(30);
    current.document.messages.push({ role: 'SYSTEM', content: 'Internal execution receipt', hasImage: false, at: '2026-10-31T15:00:00Z' });
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(viewport.scrollTop).toBe(30);
    expect(viewport.textContent).not.toContain('Internal execution receipt');
    fixture.destroy();
  });

  it.each([
    [' www.anep.edu.uy/path?q=1#section ', 'https://www.anep.edu.uy/path?q=1#section'],
    ['//www.anep.edu.uy', 'https://www.anep.edu.uy'],
    ['alertify.dev:8443/path', 'https://alertify.dev:8443/path'],
    ['localhost:8080/', 'https://localhost:8080/'],
    ['[::1]:8443/', 'https://[::1]:8443/'],
    ['http://fixture.test/', 'http://fixture.test/'],
    ['HTTPS://fixture.test/', 'HTTPS://fixture.test/'],
    ['ftp://fixture.test/', 'ftp://fixture.test/'],
    ['javascript:alert(1)', 'javascript:alert(1)'],
    ['user:password@fixture.test', 'user:password@fixture.test'],
    ['user@fixture.test', 'https://user@fixture.test'],
    [' ', ''],
  ])('normalizes %s consistently with the backend', (input, expected) => {
    expect(normalizeWizardUrl(input)).toBe(expected);
    fixture.destroy();
  });

  it('keeps the focused URL unchanged during save and heartbeat and completes it on blur', async () => {
    const url = fixture.nativeElement.querySelector('.settings input[type=url]') as HTMLInputElement;
    url.focus();
    url.value = 'www.anep.edu.uy';
    url.dispatchEvent(new Event('input', { bubbles: true }));
    await harness.save();
    harness.update(structuredClone(current));
    fixture.detectChanges();
    expect(document.activeElement).toBe(url);
    expect(url.disabled).toBe(false);
    expect(url.value).toBe('www.anep.edu.uy');
    expect(current.document.settings.url).toBe('https://www.anep.edu.uy');
    url.blur();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(url.value).toBe('https://www.anep.edu.uy');
    fixture.destroy();
  });

  it('autosaves without losing focus or selection and serializes edits made during a slow save', async () => {
    const saves: { settings: WizardSettings; finish: (value: WizardState) => void }[] = [];
    request.mockImplementation(async (operation: string, payload?: Record<string, unknown>) => {
      if (operation === 'SAVE') return new Promise<WizardState>(finish => saves.push({ settings: structuredClone(payload!['settings'] as WizardSettings), finish }));
      return structuredClone(current);
    });
    const steps = fixture.nativeElement.querySelector('.steps-panel textarea.code') as HTMLTextAreaElement;
    steps.focus();
    steps.value = 'QUERY #first';
    steps.dispatchEvent(new Event('input', { bubbles: true }));
    steps.setSelectionRange(6, 9);
    await vi.waitFor(() => expect(saves).toHaveLength(1), { timeout: 2000 });
    fixture.detectChanges();
    expect(document.activeElement).toBe(steps);
    expect(steps.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.messages .chat-working')).toBeNull();
    expect([steps.selectionStart, steps.selectionEnd]).toEqual([6, 9]);
    steps.value = 'QUERY #newer';
    steps.dispatchEvent(new Event('input', { bubbles: true }));
    steps.setSelectionRange(7, 10);
    harness.update(structuredClone(current));
    fixture.detectChanges();
    const joinedSave = harness.save();
    expect(saves).toHaveLength(1);
    current.document.settings = saves[0].settings;
    saves[0].finish(structuredClone(current));
    await vi.waitFor(() => expect(saves).toHaveLength(2));
    fixture.detectChanges();
    expect(steps.value).toBe('QUERY #newer');
    expect(document.activeElement).toBe(steps);
    expect([steps.selectionStart, steps.selectionEnd]).toEqual([7, 10]);
    current.document.settings = saves[1].settings;
    saves[1].finish(structuredClone(current));
    await joinedSave;
    fixture.detectChanges();
    expect(current.document.settings.steps).toBe('QUERY #newer');
    expect(harness.saving()).toBe(false);
    expect(document.activeElement).toBe(steps);
    expect([steps.selectionStart, steps.selectionEnd]).toEqual([7, 10]);
    fixture.destroy();
  });

  it('waits for an in-flight save and persists newer edits before opening the page', async () => {
    let finish!: (value: WizardState) => void;
    let saved!: WizardSettings;
    request.mockImplementation(async (operation: string, payload?: Record<string, unknown>) => {
      if (operation === 'SAVE') {
        const next = structuredClone(payload!['settings'] as WizardSettings);
        if (!finish) {
          saved = next;
          return new Promise<WizardState>(resolve => { finish = resolve; });
        }
        current.document.settings = next;
      }
      return structuredClone(current);
    });
    harness.patch({ url: 'www.anep.edu.uy', steps: 'QUERY #first' });
    const saving = harness.save();
    harness.patch({ steps: 'QUERY #newer' });
    const opening = harness.command('OPEN');
    expect(request.mock.calls.filter(([operation]) => operation === 'OPEN')).toHaveLength(0);
    current.document.settings = saved;
    finish(structuredClone(current));
    await Promise.all([saving, opening]);
    expect(request.mock.calls.filter(([operation]) => operation === 'SAVE' || operation === 'OPEN').map(([operation]) => operation)).toEqual(['SAVE', 'SAVE', 'OPEN']);
    expect(current.document.settings).toMatchObject({ url: 'https://www.anep.edu.uy', steps: 'QUERY #newer' });
    fixture.destroy();
  });

  it('retains local edits after a save failure and only retries on a new request', async () => {
    request.mockRejectedValueOnce(new Error('Synthetic save failure'));
    harness.settings.set({ ...current.document.settings, steps: 'QUERY #unsaved' });
    await harness.save();
    fixture.detectChanges();
    expect(harness.error()).toBe('Synthetic save failure');
    expect(harness.settings().steps).toBe('QUERY #unsaved');
    expect(harness.saving()).toBe(false);
    expect((fixture.nativeElement.querySelector('.steps-panel textarea.code') as HTMLTextAreaElement).disabled).toBe(false);
    await harness.save();
    expect(current.document.settings.steps).toBe('QUERY #unsaved');
    fixture.destroy();
  });

  it('ignores a heartbeat response from before a newer save', async () => {
    let finish!: (value: WizardState) => void;
    const stale = structuredClone(current);
    request.mockImplementation(async (operation: string, payload?: Record<string, unknown>) => {
      if (operation === 'HEARTBEAT') return new Promise<WizardState>(resolve => { finish = resolve; });
      if (operation === 'SAVE') current.document.settings = structuredClone(payload!['settings'] as WizardSettings);
      return structuredClone(current);
    });
    await vi.waitFor(() => expect(finish).toBeDefined(), { timeout: 6500 });
    harness.patch({ steps: 'QUERY #newer' });
    await harness.save();
    finish(stale);
    await fixture.whenStable();
    expect(harness.state()!.document.settings.steps).toBe('QUERY #newer');
    expect(harness.settings().steps).toBe('QUERY #newer');
    fixture.destroy();
  }, 10000);

  it('shows the running engine even when a different engine is selected for the next restart', () => {
    current.activeEngine = 'firefox';
    harness.state.set(structuredClone(current));
    harness.settings.set({ ...current.document.settings, engine: 'webkit' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.browser-badge')?.textContent).toBe('activeBrowser: Firefox');
    fixture.destroy();
  });

  it('shows the actual filtered redirect URL as readonly and keeps it outside saved settings', () => {
    const next = structuredClone(current);
    next.currentUrl = 'https://login.example.test/session/[REDACTED]';
    harness.update(next);
    fixture.detectChanges();
    const address = fixture.nativeElement.querySelector('.current-url input') as HTMLInputElement;
    expect(address.readOnly).toBe(true);
    expect(address.value).toBe(next.currentUrl);
    expect(harness.settings().url).toBe('https://example.com/');
    expect(JSON.stringify(harness.settings())).not.toContain('login.example.test');
    fixture.destroy();
  });

  it('adopts the last tested engine and retains the successful revision when the backend settles the test', async () => {
    const next = structuredClone(current);
    next.activeEngine = 'firefox';
    next.document.settings.engine = 'firefox';
    next.document.settings.browsers = ['chromium', 'firefox'];
    next.document.testedRevision = 'passing-revision';
    harness.update(next);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(harness.settings().engine).toBe('firefox');
    expect((fixture.nativeElement.querySelector('.settings select') as HTMLSelectElement).value).toBe('firefox');
    expect(fixture.nativeElement.querySelector('.browser-badge')?.textContent).toBe('activeBrowser: Firefox');
    expect(button('prepare').disabled).toBe(false);
    expect(request.mock.calls.some(([operation]) => operation === 'RESET' || operation === 'EXECUTE')).toBe(false);
    fixture.destroy();
  });

  it('explains a stale selection in the selected language and offers no old query', () => {
    current.error = 'Internal safe fallback';
    current.errorCode = 'STALE_SELECTION';
    harness.state.set(structuredClone(current));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.error')?.textContent).toBe('selectionChanged');
    expect(fixture.nativeElement.querySelector('.selection')).toBeNull();
    fixture.destroy();
  });

  it('inserts credential field references without embedding either credential value', async () => {
    harness.selectedReference.set('secrets.Login temporal');
    harness.credentialField.set('/username');
    harness.insertReference();
    harness.credentialField.set('/password');
    harness.insertReference();
    expect(harness.settings().steps).toBe('FILL SECRET "Login temporal" POINTER "/username"\nFILL SECRET "Login temporal" POINTER "/password"');
    expect(request.mock.calls.some(([operation]) => operation === 'EXECUTE')).toBe(false);
    fixture.destroy();
  });

  it('requires the explicit approval beside the chat and sends no duplicate chat request for Yes', async () => {
    const proposal = fixture.nativeElement.querySelector('.chat-panel .proposal') as HTMLElement;
    expect(proposal.textContent).toContain('QUERY #menu');
    expect(fixture.nativeElement.querySelector('.steps-panel .proposal')).toBeNull();
    expect((fixture.nativeElement.querySelector('.chat-panel textarea') as HTMLTextAreaElement).disabled).toBe(true);
    harness.message.set('Sí');
    await harness.send();
    expect(request.mock.calls.some(([operation]) => operation === 'CHAT')).toBe(false);
    button('approve').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(request.mock.calls.filter(([operation]) => operation === 'APPROVE')).toHaveLength(1);
    expect(harness.state()!.document.settings.steps).toBe('QUERY #menu\nHOVER');
    expect(fixture.nativeElement.querySelector('.proposal')).toBeNull();
    expect((fixture.nativeElement.querySelector('.chat-panel textarea') as HTMLTextAreaElement).disabled).toBe(false);
    fixture.destroy();
  });

  it('keeps approval available and avoids a save when settings only have a different property order', async () => {
    const reordered = Object.fromEntries(Object.entries(current.document.settings).reverse()) as unknown as WizardSettings;
    harness.settings.set(reordered);
    fixture.detectChanges();
    expect(button('approve').disabled).toBe(false);
    await harness.command('OBSERVE');
    fixture.detectChanges();
    expect(request.mock.calls.some(([operation]) => operation === 'SAVE')).toBe(false);
    expect(fixture.nativeElement.querySelector('.proposal')?.textContent).toContain('QUERY #menu');
    fixture.destroy();
  });

  it('discards a proposal without executing it and enables the chat again', async () => {
    button('reject').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(request.mock.calls.filter(([operation]) => operation === 'REJECT')).toHaveLength(1);
    expect(request.mock.calls.some(([operation]) => operation === 'APPROVE' || operation === 'EXECUTE')).toBe(false);
    expect(harness.state()!.document.settings.steps).toBe('');
    expect((fixture.nativeElement.querySelector('.chat-panel textarea') as HTMLTextAreaElement).disabled).toBe(false);
    fixture.destroy();
  });

  it('finds an accented secret name without accents and inserts only its reference', async () => {
    const search = fixture.nativeElement.querySelector('.steps-panel input[role="combobox"]') as HTMLInputElement;
    search.dispatchEvent(new FocusEvent('focus'));
    search.value = 'contrasena';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    const choices = fixture.nativeElement.querySelectorAll('.steps-panel [role="option"]') as NodeListOf<HTMLButtonElement>;
    expect(choices).toHaveLength(1);
    expect(choices[0].textContent).toContain('secrets.Contraseña_ANEP');
    choices[0].click();
    fixture.detectChanges();
    button('insertFill').click();
    fixture.detectChanges();
    expect(harness.settings().steps).toBe('FILL secrets.Contraseña_ANEP');
    expect(search.value).toBe('secrets.Contraseña_ANEP');
    fixture.destroy();
  });

  it('retains the failed execution receipt after a chat reply and blocks another execution until reset', async () => {
    delete current.document.proposal;
    current.restartRequired = true;
    current.document.settings.steps = 'QUERY a\nUSE';
    current.document.lastAction = { operation: 'EXECUTE', status: 'FAILED', at: '2026-10-18T23:00:00Z', error: 'Line 2 (USE): more than one element matches', result: { completed: 1, failedLine: 2, failureCode: 'AMBIGUOUS_SELECTOR' } };
    harness.state.set(structuredClone(current));
    harness.settings.set(structuredClone(current.document.settings));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.execution-receipt.failed')?.textContent).toContain('more than one element matches');
    expect(button('execute').disabled).toBe(true);
    harness.message.set('I approved it but the page did not change');
    await harness.send();
    fixture.detectChanges();
    expect(request.mock.calls.some(([operation]) => operation === 'CHAT')).toBe(true);
    expect(fixture.nativeElement.querySelector('.execution-receipt.failed')?.textContent).toContain('more than one element matches');
    expect(button('execute').disabled).toBe(true);
    fixture.destroy();
  });

  it('saves corrected steps before replay and prevents duplicate commands while it is pending', async () => {
    current.restartRequired = true;
    current.document.lastAction = { operation: 'EXECUTE', status: 'FAILED', at: '2026-10-31T12:00:00Z' };
    harness.update(structuredClone(current));
    const corrected = { ...current.document.settings, steps: 'QUERY #corrected\nUSE' };
    harness.settings.set(corrected);
    fixture.detectChanges();
    let finish!: (value: WizardState) => void;
    request.mockImplementation(async (operation: string, payload?: Record<string, unknown>) => {
      if (operation === 'SAVE') current.document.settings = structuredClone(payload!['settings'] as WizardSettings);
      if (operation === 'REPLAY') return new Promise<WizardState>(resolve => { finish = resolve; });
      return structuredClone(current);
    });
    request.mockClear();
    button('restartAndReplay').click();
    await vi.waitFor(() => expect(request.mock.calls.map(([operation]) => operation)).toEqual(['SAVE', 'REPLAY']));
    fixture.detectChanges();
    expect(current.document.settings.steps).toBe(corrected.steps);
    expect(button('restart').disabled).toBe(true);
    expect(button('restartAndReplay').disabled).toBe(true);
    await harness.command('REPLAY');
    expect(request.mock.calls.filter(([operation]) => operation === 'REPLAY')).toHaveLength(1);
    finish(structuredClone(current));
    await fixture.whenStable();
    fixture.destroy();
  });

  it('keeps URL-only restart separate from replay', async () => {
    current.restartRequired = true;
    current.document.lastAction = { operation: 'EXECUTE', status: 'FAILED', at: '2026-10-31T12:00:00Z' };
    harness.update(structuredClone(current));
    fixture.detectChanges();
    button('restart').click();
    await fixture.whenStable();
    expect(request.mock.calls.filter(([operation]) => operation === 'RESET')).toHaveLength(1);
    expect(request.mock.calls.some(([operation]) => operation === 'REPLAY' || operation === 'EXECUTE')).toBe(false);
    fixture.destroy();
  });

  it('disables both recovery buttons while busy or when the browser is closed', () => {
    current.restartRequired = true;
    current.document.lastAction = { operation: 'REPLAY', status: 'FAILED', at: '2026-10-31T12:00:00Z' };
    for (const next of [{ ...current, busy: true }, { ...current, open: false }]) {
      harness.update(structuredClone(next));
      fixture.detectChanges();
      expect(button('restart').disabled).toBe(true);
      expect(button('restartAndReplay').disabled).toBe(true);
    }
    fixture.destroy();
  });

  it('shows a rejected proposal instead of the earlier green execution receipt and permits another chat', async () => {
    delete current.document.proposal;
    current.document.lastAction = { operation: 'EXECUTE', status: 'SUCCESS', at: '2026-10-19T23:00:00Z', result: { completed: 4 } };
    current.document.proposalError = { diagnosticCode: 'PLAYWRIGHT_STEPS_INVALID', phase: 'VALIDATION', status: 'FAILED', error: 'Line 9: CHECK TEXT requires an argument', executed: false, retryable: false, remainingCorrections: 0, at: '2026-10-19T23:01:00Z' };
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.proposal-error')?.textContent).toContain('proposalFailed');
    expect(fixture.nativeElement.querySelector('.proposal-error')?.textContent).toContain('Line 9: CHECK TEXT requires an argument');
    expect(fixture.nativeElement.querySelector('.execution-receipt')).toBeNull();
    expect(fixture.nativeElement.querySelector('.proposal')).toBeNull();
    expect((fixture.nativeElement.querySelector('.chat-panel textarea') as HTMLTextAreaElement).disabled).toBe(false);
    const corrected = structuredClone(current);
    delete corrected.document.proposalError;
    corrected.document.proposal = 'QUERY #login\nUSE';
    harness.update(corrected);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.proposal-error')).toBeNull();
    expect(fixture.nativeElement.querySelector('.execution-receipt')).toBeNull();
    expect(button('approve').disabled).toBe(false);
    fixture.destroy();
  });

  it('keeps a real execution failure visible and requires restart even if a proposal error is also present', () => {
    delete current.document.proposal;
    current.restartRequired = true;
    current.document.lastAction = { operation: 'EXECUTE', status: 'FAILED', at: '2026-10-19T23:00:00Z', error: 'Browser action failed', result: { completed: 4 } };
    current.document.proposalError = { diagnosticCode: 'PLAYWRIGHT_STEPS_INVALID', phase: 'VALIDATION', status: 'FAILED', error: 'Invalid steps', executed: false, retryable: false, remainingCorrections: 0, at: '2026-10-19T23:01:00Z' };
    harness.update(structuredClone(current));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.execution-receipt.failed')?.textContent).toContain('Browser action failed');
    expect(button('execute').disabled).toBe(true);
    fixture.destroy();
  });

  it('shows linked parameters as read-only and does not replace them when patched', async () => {
    current.document.parameterBindings = { url: { source: 'SECRET', id: 9, name: 'Private URL', version: 0 }, steps: { source: 'CONFIGURATION', id: 8, name: 'Shared plan', version: 2 } };
    current.document.settings.url = '';
    current.document.settings.steps = '';
    harness.update(structuredClone(current));
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement.querySelector('.settings input[type=url]') as HTMLInputElement).disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.code-editor')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Shared plan');
    harness.patch({ url: 'https://replacement.invalid/', steps: 'QUERY #replacement' });
    expect(harness.settings().url).toBe('');
    expect(harness.settings().steps).toBe('');
    fixture.destroy();
  });

  it('requests explicit confirmation for parameter procedures and sends the server token only after acceptance', async () => {
    const confirmation = { ...structuredClone(current), parameterConfirmation: { token: 'single-use-token', procedures: [{ parameter: 'url', name: 'URL provider' }] } };
    const ask = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    request.mockResolvedValueOnce(confirmation);
    await harness.command('OPEN');
    expect(request.mock.calls.filter(([operation]) => operation === 'OPEN')).toHaveLength(1);
    request.mockResolvedValueOnce(confirmation).mockResolvedValueOnce(structuredClone(current));
    await harness.command('OPEN');
    expect(ask).toHaveBeenCalledWith(expect.stringContaining('URL provider (url)'));
    expect(request).toHaveBeenLastCalledWith('OPEN', expect.objectContaining({ parameterConfirmationToken: 'single-use-token' }));
    ask.mockRestore();
    fixture.destroy();
  });
});
