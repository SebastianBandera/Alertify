import { Component, WritableSignal, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EMPTY } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AlertApiService } from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { PlaywrightRemoteViewComponent } from './playwright-remote-view.component';
import { PlaywrightWizardApiService, WizardSettings, WizardState } from './playwright-wizard-api.service';
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
    request = vi.fn(async (operation: string) => {
      if (operation === 'LIST') return [];
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

  it('shows the running engine even when a different engine is selected for the next restart', () => {
    current.activeEngine = 'firefox';
    harness.state.set(structuredClone(current));
    harness.settings.set({ ...current.document.settings, engine: 'webkit' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.browser-badge')?.textContent).toBe('activeBrowser: Firefox');
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
});
