import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { AiModuleApiService, AiSettings } from '../../core/api/ai-module-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AiModuleComponent } from './ai-module.component';

describe('AiModuleComponent conversation history setting', () => {
  it('loads and saves an explicit false without losing the rest of General settings', async () => {
    const settings: AiSettings = {
      enabled: true, provider: 'CODEX', model: 'configured-model', reasoningEffort: 'low',
      agentInstructions: 'existing instructions', refreshPolicy: 'ON_USE', reviewerModel: '',
      reviewerReasoningEffort: 'low', conversationHistorySearchEnabled: true,
    };
    const api = {
      module: vi.fn().mockResolvedValue({ workerAvailable: true, settings, sessionStatus: 'ACTIVE' }),
      update: vi.fn().mockImplementation(async (value: AiSettings) => value),
    };
    await TestBed.configureTestingModule({
      imports: [AiModuleComponent],
      providers: [
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
