import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { EMPTY } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApplicationConfiguration, ConfigurationApiService } from '../../core/api/configuration-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { ConfigsComponent } from './configs.component';

const configuration = { id: 1, name: 'Expression', valueType: 'EXPRESSION', value: '{{utils.now}}', tags: [], usageCount: 0 } as unknown as ApplicationConfiguration;

describe('Configuration list on-demand value', () => {
  let fixture: ComponentFixture<ConfigsComponent>;
  const api = {
    listConfigurations: vi.fn(), listTags: vi.fn(), getExpressionSuggestions: vi.fn(), getBinaryLimits: vi.fn(), evaluateExpression: vi.fn(),
  };

  beforeEach(async () => {
    vi.resetAllMocks();
    api.listConfigurations.mockResolvedValue({ content: [configuration], page: { number: 0, totalElements: 1, totalPages: 1 } });
    api.listTags.mockResolvedValue([]);
    api.getExpressionSuggestions.mockResolvedValue({ configurations: [], environmentVariables: [], utilities: [], utilityFunctions: [] });
    api.getBinaryLimits.mockResolvedValue({ maximumBytes: 1024 });
    await TestBed.configureTestingModule({
      imports: [ConfigsComponent],
      providers: [
        provideRouter([]),
        { provide: ConfigurationApiService, useValue: api },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ConfigsComponent);
    fixture.detectChanges();
    await settle();
  });

  afterEach(() => fixture.destroy());

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function refresh(): Promise<void> {
    await (fixture.componentInstance as unknown as { loadConfigurations(): Promise<void> }).loadConfigurations();
    await settle();
  }

  it('evaluates only once after a click and forgets the preview on refresh', async () => {
    api.evaluateExpression.mockResolvedValue({ value: 'one-time result' });
    expect(api.evaluateExpression).not.toHaveBeenCalled();
    fixture.nativeElement.querySelector('.value-preview--action').click();
    await settle();
    expect(fixture.nativeElement.querySelector('td .value-preview').textContent).toBe('one-time result');
    expect(fixture.nativeElement.querySelector('.value-preview--action')).toBeNull();
    expect(api.evaluateExpression).toHaveBeenCalledTimes(1);
    await refresh();
    expect(fixture.nativeElement.querySelector('.value-preview--action').textContent).toContain('configs.expression.onDemand');
  });

  it('ignores an in-flight evaluation after the list is refreshed', async () => {
    let complete!: (value: { value: string }) => void;
    api.evaluateExpression.mockReturnValue(new Promise((resolve) => { complete = resolve; }));
    fixture.nativeElement.querySelector('.value-preview--action').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.value-preview--action').disabled).toBe(true);
    await refresh();
    complete({ value: 'old result' });
    await settle();
    expect(fixture.nativeElement.querySelector('.value-preview--action').textContent).toContain('configs.expression.onDemand');
    expect(fixture.nativeElement.textContent).not.toContain('old result');
  });

  it('allows retry after failure and preserves an empty successful result', async () => {
    api.evaluateExpression.mockRejectedValueOnce(new Error('sensitive detail')).mockResolvedValueOnce({ value: '' });
    fixture.nativeElement.querySelector('.value-preview--action').click();
    await settle();
    expect(fixture.nativeElement.querySelector('.value-preview-error').textContent).toContain('configs.preview.error');
    expect(fixture.nativeElement.textContent).not.toContain('sensitive detail');
    fixture.nativeElement.querySelector('.value-preview--action').click();
    await settle();
    expect(fixture.nativeElement.querySelector('td .value-preview').textContent).toBe('configs.preview.empty');
    expect(fixture.nativeElement.querySelector('.value-preview--action')).toBeNull();
  });
});
