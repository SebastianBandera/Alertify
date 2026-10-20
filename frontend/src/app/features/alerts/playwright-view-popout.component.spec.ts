import { Component, input, output, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocalizationService } from '../../core/i18n/localization.service';
import { PlaywrightRemoteViewComponent } from './playwright-remote-view.component';
import { PlaywrightViewPopoutComponent } from './playwright-view-popout.component';

@Component({ selector: 'app-playwright-remote-view', template: '' })
class RemoteStub {
  readonly draftId = input('');
  readonly elementSelected = output<unknown>();
  readonly scrolled = output<number>();
}

describe('PlaywrightViewPopoutComponent', () => {
  const originalOpener = Object.getOwnPropertyDescriptor(window, 'opener');
  afterEach(() => {
    if (originalOpener) Object.defineProperty(window, 'opener', originalOpener);
    else Reflect.deleteProperty(window, 'opener');
  });

  it('requests current state and accepts the filtered URL only from its own opener and draft', async () => {
    const opener = { postMessage: vi.fn() };
    Object.defineProperty(window, 'opener', { configurable: true, value: opener });
    await TestBed.configureTestingModule({
      imports: [PlaywrightViewPopoutComponent],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: new Map([['id', 'draft'], ['engine', 'chromium']]) } } },
        { provide: LocalizationService, useValue: { locale: signal('es-UY'), translateDynamic: (key: string) => key } },
      ],
    }).overrideComponent(PlaywrightViewPopoutComponent, { remove: { imports: [PlaywrightRemoteViewComponent] }, add: { imports: [RemoteStub] } }).compileComponents();
    const fixture = TestBed.createComponent(PlaywrightViewPopoutComponent);
    expect(opener.postMessage).toHaveBeenCalledWith({ type: 'WIZARD_READY', id: 'draft' }, location.origin);
    const data = { type: 'WIZARD_ENGINE', id: 'draft', engine: 'firefox', currentUrl: 'https://redirect.test/[REDACTED]' };
    window.dispatchEvent(new MessageEvent('message', { origin: 'https://untrusted.test', source: opener as unknown as Window, data }));
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('input') as HTMLInputElement).value).toBe('');
    window.dispatchEvent(new MessageEvent('message', { origin: location.origin, source: opener as unknown as Window, data }));
    fixture.detectChanges();
    const address = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    expect(address.value).toBe(data.currentUrl);
    expect(address.readOnly).toBe(true);
    fixture.destroy();
  });
});
