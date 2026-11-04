import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AlertMessageService } from '../../../core/alert-messages/alert-message.service';
import { AuthService } from '../../../core/auth/auth.service';
import { PipeApiService } from '../../../core/api/pipe-api.service';
import { RUNTIME_CONFIG } from '../../../core/config/runtime-config';
import { LocalizationService } from '../../../core/i18n/localization.service';
import { DashboardPipeCard } from './dashboard-pipe';
import { DashboardPipeLiveService } from './dashboard-pipe-live.service';
import { DashboardPipesComponent } from './dashboard-pipes.component';

const CARD: DashboardPipeCard = {
  id: 4, version: 1, name: 'Mixed Pipe', description: null, enabled: true,
  allowConcurrentExecutions: false, tags: [], execution: null, observedAt: '2026-11-03T12:00:00Z',
  steps: [{
    templateKey: 'example.Procedure', alertExecution: null,
    result: { key: 'procedure', position: 0, type: 'PROCEDURE', phase: 'MAIN', resourceId: 1,
      resourceName: 'Get token', status: 'PENDING', outcome: null, resourceExecutionId: null,
      startedAt: null, finishedAt: null, errorCode: null },
  }],
};

describe('dashboard Pipe menu focus', () => {
  let fixture: ComponentFixture<DashboardPipesComponent>;

  beforeEach(async () => {
    vi.stubGlobal('ResizeObserver', class {
      observe(): void { }
      disconnect(): void { }
      unobserve(): void { }
    });
    await TestBed.configureTestingModule({
      imports: [DashboardPipesComponent],
      providers: [
        { provide: DashboardPipeLiveService, useValue: { cards: signal([CARD]), muted: signal({}), loading: signal(false), error: signal(false), mute: vi.fn() } },
        { provide: LocalizationService, useValue: { translateDynamic: (key: string) => key, translate: (key: string) => key } },
        { provide: AuthService, useValue: { isAdmin: true, canRunPipesFromDashboard: false } },
        { provide: PipeApiService, useValue: { run: vi.fn() } },
        { provide: AlertMessageService, useValue: { tileMessage: () => '' } },
        { provide: ActivatedRoute, useValue: { queryParamMap: new BehaviorSubject(convertToParamMap({})) } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: '' } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DashboardPipesComponent);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    fixture?.destroy();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps the menu open when focusing its first action would otherwise scroll the page', async () => {
    const nativeFocus = HTMLElement.prototype.focus;
    vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(function(this: HTMLElement, options?: FocusOptions) {
      nativeFocus.call(this, options);
      // Browsers may scroll a focused item into view. Model that behavior only
      // when the component did not explicitly prevent focus-induced scrolling.
      if (this.closest('.pipe-menu') && !options?.preventScroll) window.dispatchEvent(new Event('scroll'));
    });
    (fixture.nativeElement.querySelector('.pipe-kebab') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="menu"]')).not.toBeNull();
    expect(document.activeElement?.getAttribute('role')).toBe('menuitem');
  });

  it('still dismisses an open menu when the user scrolls the page', async () => {
    (fixture.nativeElement.querySelector('.pipe-kebab') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="menu"]')).not.toBeNull();
    window.dispatchEvent(new Event('scroll'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="menu"]')).toBeNull();
  });

  it('consumes a Pipe detail link and opens a later link for the same Pipe while mounted', async () => {
    const params = TestBed.inject(ActivatedRoute).queryParamMap as BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    const navigate = vi.mocked(TestBed.inject(Router).navigate);
    navigate.mockImplementation(async (_commands, extras) => {
      if (extras?.queryParams?.['pipe'] === null) params.next(convertToParamMap({}));
      return true;
    });
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeNull();
    params.next(convertToParamMap({ pipe: '4' }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')?.getAttribute('aria-label')).toBe(CARD.name);
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({
      queryParams: { pipe: null }, queryParamsHandling: 'merge', replaceUrl: true,
    }));
    expect(params.value.get('pipe')).toBeNull();

    (fixture.nativeElement.querySelector('.modal__footer button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeNull();
    navigate.mockClear();
    params.next(convertToParamMap({ pipe: '4' }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')?.getAttribute('aria-label')).toBe(CARD.name);
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { pipe: null }, replaceUrl: true }));
  });

  it('reports zero visible Pipes when the search excludes every Pipe', async () => {
    expect(fixture.componentInstance.visibleCount()).toBe(1);
    fixture.componentRef.setInput('search', 'does-not-match-any-pipe');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.visibleCount()).toBe(0);
    expect(fixture.nativeElement.querySelector('.pipe-card')).toBeNull();
  });
});
