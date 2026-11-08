import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { EMPTY, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ProcedureApiService, TotpQrAnalysisResult } from '../../core/api/procedure-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { ProceduresComponent } from './procedures.component';

const result: TotpQrAnalysisResult = { secretId: 99, secretName: 'Synthetic TOTP secret', algorithm: 'SHA256', digits: 8, periodSeconds: 60, suggestedProcedureName: 'Synthetic TOTP procedure' };
const template = {
  id: 7, templateKey: 'app.alertify.procedures.templates.TotpProcedureTemplate', nameKey: 'TOTP', tags: [], outputs: [],
  parameters: ['secret', 'algorithm', 'digits', 'periodSeconds'].map((key, order) => ({
    id: order + 1, key, order, javaType: 'java.lang.String', labelKey: key, descriptionKey: key,
    defaultValue: '', bindingAllowed: true, required: true, options: key === 'algorithm' ? ['SHA1', 'SHA256', 'SHA512'] : key === 'digits' ? ['6', '8'] : [],
    allowedSources: ['TEXT', 'SECRET'], allowedConfigurationValueTypes: [], allowedSecretValueTypes: ['STRING'],
  })),
};

interface WizardHarness {
  loading(): boolean;
  wizardStatus(): string;
  wizardError(): string | null;
  editorOpen(): boolean;
  form(): { name: string; parameters: Record<string, { source: string; textValue: string; secretId: number | null }> };
  onTotpQrFileChange(event: Event): Promise<void>;
  onTotpQrPaste(event: ClipboardEvent): Promise<void>;
}

describe('TOTP QR image input', () => {
  let fixture: ComponentFixture<ProceduresComponent>;
  let wizard: WizardHarness;
  let analyze: ReturnType<typeof vi.fn>;
  const image = () => new File(['synthetic image bytes'], 'qr.png', { type: 'image/png' });

  beforeEach(async () => {
    localStorage.clear();
    analyze = vi.fn().mockResolvedValue(result);
    const page = { content: [], page: { number: 0, totalPages: 0, totalElements: 0 } };
    vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    await TestBed.configureTestingModule({
      imports: [ProceduresComponent],
      providers: [
        { provide: ProcedureApiService, useValue: {
          listTemplates: async () => [template], listTags: async () => [], bindingOptions: async () => ({ configurations: [], secrets: [], procedures: [], pipes: [] }),
          listProcedures: async () => page, listExecutions: async () => page, analyzeTotpQr: analyze,
        } },
        { provide: AuthService, useValue: { userIdentifier: 'totp-test-user', getAccessToken: async () => 'test-token' } },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: 'https://example.test' } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ tab: 'wizards' }) }, queryParamMap: of(convertToParamMap({ tab: 'wizards' })) } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ProceduresComponent);
    wizard = fixture.componentInstance as unknown as WizardHarness;
    fixture.detectChanges();
    await vi.waitFor(() => expect(wizard.loading()).toBe(false));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('button.wizard-card').click();
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture?.destroy();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function fileEvent(file?: File): { event: Event; input: { files: File[]; value: string } } {
    const input = { files: file ? [file] : [], value: file ? 'fake selected file' : '' };
    return { event: { target: input } as unknown as Event, input };
  }

  function pasteEvent(file?: File): ClipboardEvent {
    return { clipboardData: { items: file ? [{ type: file.type, getAsFile: () => file }] : [] }, preventDefault: vi.fn() } as unknown as ClipboardEvent;
  }

  it('opens an image-only single-file picker from the upload button', () => {
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input[type=file]');
    expect(input.accept).toBe('image/*');
    expect(input.multiple).toBe(false);
    const click = vi.spyOn(input, 'click').mockImplementation(() => {});
    const root: HTMLElement = fixture.nativeElement;
    const button = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(button => button.textContent?.includes('totpQr.selectFile'))!;
    button.click();
    expect(click).toHaveBeenCalledOnce();
    expect(analyze).not.toHaveBeenCalled();
  });

  it.each(['file', 'paste'])('prefills the same TOTP form from %s', async source => {
    const file = image();
    if (source === 'file') {
      const { event, input } = fileEvent(file);
      await wizard.onTotpQrFileChange(event);
      expect(input.value).toBe('');
    } else {
      const event = pasteEvent(file);
      await wizard.onTotpQrPaste(event);
      expect(event.preventDefault).toHaveBeenCalledOnce();
    }
    expect(analyze).toHaveBeenCalledExactlyOnceWith(file);
    expect(wizard.editorOpen()).toBe(true);
    expect(wizard.form().name).toBe(result.suggestedProcedureName);
    expect(wizard.form().parameters['secret']).toMatchObject({ source: 'SECRET', secretId: 99 });
    expect(wizard.form().parameters['algorithm']).toMatchObject({ source: 'OPTION', textValue: 'SHA256' });
    expect(wizard.form().parameters['digits']).toMatchObject({ source: 'OPTION', textValue: '8' });
    expect(wizard.form().parameters['periodSeconds']).toMatchObject({ source: 'TEXT', textValue: '60' });
  });

  it('does nothing on picker cancellation or a paste without an image', async () => {
    await wizard.onTotpQrFileChange(fileEvent().event);
    await wizard.onTotpQrPaste(pasteEvent());
    expect(analyze).not.toHaveBeenCalled();
    expect(wizard.wizardStatus()).toBe('idle');
    expect(wizard.editorOpen()).toBe(false);
  });

  it('shows the analysis error and permits retrying the same file', async () => {
    analyze.mockRejectedValueOnce(new Error('Unreadable QR image'));
    const file = image();
    await wizard.onTotpQrFileChange(fileEvent(file).event);
    expect(wizard.wizardStatus()).toBe('error');
    expect(wizard.wizardError()).toBe('Unreadable QR image');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Unreadable QR image');
    await wizard.onTotpQrFileChange(fileEvent(file).event);
    expect(analyze).toHaveBeenCalledTimes(2);
    expect(wizard.wizardStatus()).toBe('idle');
    expect(wizard.wizardError()).toBeNull();
    expect(wizard.editorOpen()).toBe(true);
  });

  it('blocks file and clipboard submissions while an analysis is in progress', async () => {
    let complete!: (value: TotpQrAnalysisResult) => void;
    analyze.mockImplementationOnce(() => new Promise<TotpQrAnalysisResult>(resolve => { complete = resolve; }));
    const pending = wizard.onTotpQrFileChange(fileEvent(image()).event);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[type=file]').disabled).toBe(true);
    await wizard.onTotpQrPaste(pasteEvent(image()));
    await wizard.onTotpQrFileChange(fileEvent(image()).event);
    expect(analyze).toHaveBeenCalledOnce();
    complete(result);
    await pending;
    expect(wizard.wizardStatus()).toBe('idle');
  });
});
