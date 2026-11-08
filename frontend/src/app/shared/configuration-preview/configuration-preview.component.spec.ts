import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApplicationConfiguration, ConfigurationApiService } from '../../core/api/configuration-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { ConfigurationPreviewComponent } from './configuration-preview.component';
import { ConfigurationPreviewService } from './configuration-preview.service';

const configuration = (valueType: string, value: unknown, id = 1) => ({ id, name: `Config ${id}`, valueType, value }) as ApplicationConfiguration;
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
};

describe('Ephemeral configuration previews', () => {
  let fixture: ComponentFixture<ConfigurationPreviewComponent>;
  let previews: ConfigurationPreviewService;
  const api = { getConfiguration: vi.fn(), evaluateExpression: vi.fn() };

  beforeEach(() => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      imports: [ConfigurationPreviewComponent],
      providers: [
        { provide: ConfigurationApiService, useValue: api },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key } },
      ],
    });
    previews = TestBed.inject(ConfigurationPreviewService);
  });

  afterEach(() => fixture?.destroy());

  async function show(id: number, wait = true): Promise<void> {
    fixture = TestBed.createComponent(ConfigurationPreviewComponent);
    fixture.componentRef.setInput('configurationId', id);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    if (wait) await finished();
  }

  async function finished(): Promise<void> {
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).not.toContain('configs.expression.evaluating');
    });
  }

  it.each([
    ['BOOLEAN', false, 'false'], ['INTEGER', 0, '0'], ['STRING', '', ''],
    ['JSON', { items: [false, 0] }, '{"items":[false,0]}'],
  ])('preserves %s values without treating falsy values as missing', async (type, value, expected) => {
    expect(await previews.resolve(configuration(type, value))).toBe(expected);
    expect(api.evaluateExpression).not.toHaveBeenCalled();
  });

  it('uses the existing expression evaluator and shows only metadata for binary values', async () => {
    api.evaluateExpression.mockResolvedValue({ value: 'resolved' });
    expect(await previews.resolve(configuration('EXPRESSION', '{{configs.BASE}}'))).toBe('resolved');
    expect(api.evaluateExpression).toHaveBeenCalledWith({ configurationId: 1, configurationName: 'Config 1', expression: '{{configs.BASE}}' });
    expect(await previews.resolve({ ...configuration('BINARY', null), binaryFileName: 'data.bin', binarySize: 123 })).toBe('data.bin (123 B)');
  });

  it('deduplicates bindings and resolves again after invalidation', async () => {
    api.getConfiguration.mockResolvedValue(configuration('INTEGER', 3));
    expect(await Promise.all([previews.get(1), previews.get(1)])).toEqual(['3', '3']);
    expect(api.getConfiguration).toHaveBeenCalledTimes(1);
    previews.clear();
    await previews.get(1);
    expect(api.getConfiguration).toHaveBeenCalledTimes(2);
  });

  it('automatically renders an empty result distinctly and treats returned HTML as text', async () => {
    api.getConfiguration.mockResolvedValue(configuration('STRING', ''));
    await show(1);
    expect(fixture.nativeElement.textContent).toContain('configs.preview.empty');
    api.getConfiguration.mockResolvedValue(configuration('STRING', '<img src=x onerror=alert(1)>', 2));
    fixture.componentRef.setInput('configurationId', 2);
    fixture.detectChanges();
    await finished();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('input, textarea')).toBeNull();
  });

  it('does not let an older selection overwrite the selected configuration', async () => {
    const first = deferred<ApplicationConfiguration>();
    api.getConfiguration.mockReturnValueOnce(first.promise).mockResolvedValueOnce(configuration('INTEGER', 22, 2));
    await show(1, false);
    expect(fixture.nativeElement.textContent).toContain('configs.expression.evaluating');
    fixture.componentRef.setInput('configurationId', 2);
    fixture.detectChanges();
    await fixture.whenStable();
    first.resolve(configuration('INTEGER', 11));
    await finished();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('pre').textContent).toBe('22');
  });

  it('discards results started before refresh and does not reinsert them into the cache', async () => {
    const first = deferred<ApplicationConfiguration>();
    api.getConfiguration.mockReturnValueOnce(first.promise).mockResolvedValueOnce(configuration('INTEGER', 2));
    await show(1, false);
    previews.clear();
    fixture.detectChanges();
    await fixture.whenStable();
    first.resolve(configuration('INTEGER', 1));
    await finished();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('pre').textContent).toBe('2');
    expect(await previews.get(1)).toBe('2');
  });

  it('shows a sanitized error, retries, and clears the result when unbound', async () => {
    api.getConfiguration.mockRejectedValueOnce(new Error('sensitive diagnostic')).mockResolvedValueOnce(configuration('BOOLEAN', false));
    await show(1);
    expect(fixture.nativeElement.textContent).toContain('configs.preview.error');
    expect(fixture.nativeElement.textContent).not.toContain('sensitive diagnostic');
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    await finished();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('pre').textContent).toBe('false');
    fixture.componentRef.setInput('configurationId', null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('section')).toBeNull();
  });
});
