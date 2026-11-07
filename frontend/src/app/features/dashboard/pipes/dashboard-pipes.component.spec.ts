import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AlertMessageService } from '../../../core/alert-messages/alert-message.service';
import { AuthService } from '../../../core/auth/auth.service';
import { PipeApiService, PipeExecution, PipeOutcome } from '../../../core/api/pipe-api.service';
import { RUNTIME_CONFIG } from '../../../core/config/runtime-config';
import { LocalizationService } from '../../../core/i18n/localization.service';
import { DashboardPipeCard } from './dashboard-pipe';
import { DashboardPipeLiveService } from './dashboard-pipe-live.service';
import { DashboardPipesComponent } from './dashboard-pipes.component';
import { DEFAULT_VIEW_SETTINGS } from '../dashboard-view-settings';

const CARD: DashboardPipeCard = {
  id: 4, version: 1, name: 'Mixed Pipe', description: null, enabled: true,
  allowConcurrentExecutions: false, tags: [], execution: null, observedAt: '2026-11-03T12:00:00Z',
  lastOutcome: null, stateChangedAt: null,
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
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps Pipe order on start, progress and a repeated final outcome, including initial load during a run', () => {
    const live = TestBed.inject(DashboardPipeLiveService).cards as ReturnType<typeof signal<DashboardPipeCard[]>>;
    const first = pipeCard(1, 'First', 'WARN', '2026-11-01T12:00:00Z');
    const second = pipeCard(2, 'Second', 'WARN', '2026-11-02T12:00:00Z');
    const ids = () => [...fixture.nativeElement.querySelectorAll('.pipe-card')].map((element: Element) => element.getAttribute('data-pipe-id'));
    live.set([first, second]);
    fixture.detectChanges();
    expect(ids()).toEqual(['2', '1']);

    const running = { ...first, execution: { ...first.execution!, status: 'RUNNING' as const, outcome: null, startedAt: '2026-11-05T12:00:00Z', finishedAt: null } };
    live.set([running, second]);
    fixture.detectChanges();
    expect(ids()).toEqual(['2', '1']);
    expect(fixture.nativeElement.querySelector('[data-pipe-id="1"] .running')).not.toBeNull();
    live.set([{ ...running, observedAt: '2026-11-05T12:00:05Z' }, second]);
    fixture.detectChanges();
    expect(ids()).toEqual(['2', '1']);

    live.set([{ ...first, execution: { ...first.execution!, startedAt: '2026-11-05T12:00:00Z', finishedAt: '2026-11-05T12:00:10Z' } }, second]);
    fixture.detectChanges();
    expect(ids()).toEqual(['2', '1']);

    fixture.destroy();
    live.set([running, second]);
    fixture = TestBed.createComponent(DashboardPipesComponent);
    fixture.detectChanges();
    expect(ids()).toEqual(['2', '1']);
  });

  it('reorders Pipes when their final severity changes', () => {
    const live = TestBed.inject(DashboardPipeLiveService).cards as ReturnType<typeof signal<DashboardPipeCard[]>>;
    const first = pipeCard(1, 'First', 'SUCCESS', '2026-11-01T12:00:00Z');
    const second = pipeCard(2, 'Second', 'WARN', '2026-11-02T12:00:00Z');
    live.set([first, second]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pipe-card').getAttribute('data-pipe-id')).toBe('2');
    live.set([{ ...first, lastOutcome: 'ERROR', stateChangedAt: '2026-11-05T12:00:10Z', execution: { ...first.execution!, outcome: 'ERROR' } }, second]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pipe-card').getAttribute('data-pipe-id')).toBe('1');
  });

  it('dismisses an execution notice after six seconds', async () => {
    vi.useFakeTimers();
    await fixture.componentInstance['run'](CARD);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')?.textContent).toBe('dashboard.pipe.started');
    vi.advanceTimersByTime(5_999);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')).not.toBeNull();
    vi.advanceTimersByTime(1);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')).toBeNull();
  });

  it('gives a repeated execution notice its own full six seconds', async () => {
    vi.useFakeTimers();
    await fixture.componentInstance['run'](CARD);
    vi.advanceTimersByTime(3_000);
    await fixture.componentInstance['run'](CARD);
    vi.advanceTimersByTime(3_000);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')).not.toBeNull();
    vi.advanceTimersByTime(3_000);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')).toBeNull();
  });

  it('also dismisses a failed execution request notice', async () => {
    vi.useFakeTimers();
    vi.mocked(TestBed.inject(PipeApiService).run).mockRejectedValue(new Error('Request failed'));
    await fixture.componentInstance['run'](CARD);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')?.textContent).toBe('dashboard.pipe.requestFailed');
    vi.advanceTimersByTime(6_000);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dashboard__notice')).toBeNull();
  });

  it('cancels the notice timer when the dashboard is destroyed', async () => {
    vi.useFakeTimers();
    await fixture.componentInstance['run'](CARD);
    fixture.destroy();
    vi.advanceTimersByTime(6_000);
    expect(fixture.componentInstance['notice']()).toBe('dashboard.pipe.started');
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

  it.each([
    [0, '0 s'], [417, '0.4 s'], [4300, '4.3 s'], [12000, '12 s'], [125600, '125.6 s'],
  ])('shows a total duration of %s ms as %s even without horizontal overflow', (durationMillis, text) => {
    const card = pipeCard(4, 'Timed Pipe', 'SUCCESS', '2026-11-01T12:00:00Z');
    setCards([{ ...card, execution: { ...card.execution!, durationMillis } }]);
    expect(fixture.nativeElement.querySelector('.pipe-total-duration')?.textContent.trim()).toBe(text);
    fixture.componentRef.setInput('settings', { ...DEFAULT_VIEW_SETTINGS, minified: true });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pipe-total-duration')?.textContent.trim()).toBe(text);
  });

  it('hides a total duration when it is unavailable', () => {
    expect(fixture.nativeElement.querySelector('.pipe-total-duration')).toBeNull();
    const card = pipeCard(4, 'Running Pipe', 'WARN', '2026-11-01T12:00:00Z');
    setCards([{ ...card, execution: { ...card.execution!, status: 'RUNNING', durationMillis: null } }]);
    expect(fixture.nativeElement.querySelector('.pipe-total-duration')).toBeNull();
  });

  it.each(['SUCCESS', 'WARN', 'ERROR'] as const)('colors the header for the global %s state', (outcome) => {
    setCards([pipeCard(4, 'State Pipe', outcome, '2026-11-01T12:00:00Z')]);
    expect(fixture.nativeElement.querySelector('.pipe-header').classList.contains('alert-card--' + outcome.toLowerCase())).toBe(true);
  });

  it('respects flat greens and keeps the prior severity while running', () => {
    const card = pipeCard(4, 'State Pipe', 'SUCCESS', new Date(Date.now() - 10 * 24 * 3_600_000).toISOString());
    setCards([card]);
    expect(fixture.nativeElement.querySelector('.pipe-header').classList.contains('alert-card--success-very-old')).toBe(true);
    fixture.componentRef.setInput('settings', { ...DEFAULT_VIEW_SETTINGS, flatGreens: true });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pipe-header').classList.contains('alert-card--success-flat')).toBe(true);
    setCards([{ ...card, lastOutcome: 'ERROR', execution: { ...card.execution!, outcome: null, status: 'RUNNING' } }]);
    expect(fixture.nativeElement.querySelector('.pipe-header').classList.contains('alert-card--error')).toBe(true);
    expect(fixture.nativeElement.querySelector('.pipe-header .running')).not.toBeNull();
  });

  it.each(['ALERT', 'PROCEDURE'] as const)('separates the %s name link from the detail expansion button', async (type) => {
    const card = { ...CARD, steps: [{ ...CARD.steps[0], result: { ...CARD.steps[0].result, type } }] };
    setCards([card]);
    fixture.componentInstance['openDetail'](card);
    fixture.detectChanges();
    await fixture.whenStable();
    const button = fixture.nativeElement.querySelector('.pipe-detail-toggle') as HTMLButtonElement;
    const link = fixture.nativeElement.querySelector('.pipe-resource-link') as HTMLAnchorElement;
    expect(link.textContent?.trim()).toBe('Get token');
    expect(link.closest('button')).toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('false');
    // Clicking the name cannot reach the sibling expansion button.
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, ctrlKey: true }));
    expect(fixture.componentInstance['expanded']()).toBeNull();
    button.click();
    fixture.detectChanges();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('.pipe-step-detail')).not.toBeNull();
    button.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pipe-step-detail')).toBeNull();
  });

  it('keeps viewer step names as plain text while preserving expansion', () => {
    Object.assign(TestBed.inject(AuthService), { isAdmin: false });
    fixture.componentInstance['openDetail'](CARD);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pipe-resource-link')).toBeNull();
    expect(fixture.nativeElement.querySelector('.pipe-detail-toggle')).not.toBeNull();
  });

  it('follows the running and next pending steps once, pauses for manual scrolling and resumes on the next step', () => {
    const card = runningCard(2);
    const { track, scroll } = geometry(card);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).toHaveBeenLastCalledWith({ left: 435, behavior: 'smooth' });
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).toHaveBeenCalledTimes(1);
    track.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    track.scrollLeft = 0;
    fixture.componentInstance['followStep'](card, track, true);
    expect(scroll).toHaveBeenCalledTimes(1);
    const next = runningCard(3);
    setCards([next]);
    fixture.componentInstance['followStep'](next, track);
    expect(scroll).toHaveBeenLastCalledWith({ left: 700, behavior: 'smooth' });
  });

  it('does not move a fully visible pair and prioritizes the running step on a narrow viewport', () => {
    const card = runningCard(0);
    const { track, scroll } = geometry(card);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).not.toHaveBeenCalled();
    const next = runningCard(2);
    setCards([next]);
    Object.defineProperty(track, 'clientWidth', { configurable: true, value: 300 });
    fixture.componentInstance['followStep'](next, track, true);
    expect(scroll).toHaveBeenLastCalledWith({ left: 530, behavior: 'smooth' });
  });

  it('waits while hidden or covered by a modal and follows when visible again', () => {
    const card = runningCard(2);
    const { track, scroll } = geometry(card);
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).not.toHaveBeenCalled();
    visibility.mockReturnValue('visible');
    fixture.componentInstance['selected'].set(card);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).not.toHaveBeenCalled();
    fixture.componentInstance['selected'].set(null);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).toHaveBeenCalledTimes(1);
  });

  it('does not scroll an offscreen Pipe and catches up when it enters the viewport', () => {
    const card = runningCard(3);
    const { track, scroll } = geometry(card);
    vi.mocked(track.getBoundingClientRect).mockReturnValue({ left: 0, right: 600, top: -200, bottom: -40, width: 600, height: 160 } as DOMRect);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).not.toHaveBeenCalled();
    vi.mocked(track.getBoundingClientRect).mockReturnValue({ left: 0, right: 600, top: 0, bottom: 160, width: 600, height: 160 } as DOMRect);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).toHaveBeenLastCalledWith({ left: 700, behavior: 'smooth' });
  });

  it('respects reduced motion, follows closing steps and resets manual pause for a new execution', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    const card = runningCard(4);
    const { track, scroll } = geometry(card);
    fixture.componentInstance['followStep'](card, track);
    expect(scroll).toHaveBeenLastCalledWith({ left: 965, behavior: 'instant' });
    fixture.componentInstance['pauseFollow'](card);
    track.scrollLeft = 0;
    const nextRun = { ...card, execution: { ...card.execution!, executionId: 'next-execution' } };
    setCards([nextRun]);
    fixture.componentInstance['followStep'](nextRun, track);
    expect(scroll).toHaveBeenCalledTimes(2);
    const completed = { ...nextRun, execution: { ...nextRun.execution!, status: 'COMPLETED' as const } };
    fixture.componentInstance['followStep'](completed, track);
    expect(scroll).toHaveBeenCalledTimes(2);
  });

  function setCards(cards: DashboardPipeCard[]): void {
    (TestBed.inject(DashboardPipeLiveService).cards as ReturnType<typeof signal<DashboardPipeCard[]>>).set(cards);
    fixture.detectChanges();
  }

  function runningCard(index: number): DashboardPipeCard {
    const card = pipeCard(4, 'Following Pipe', 'SUCCESS', '2026-11-01T12:00:00Z');
    return { ...card, execution: { ...card.execution!, status: 'RUNNING', outcome: null }, steps: Array.from({ length: 6 }, (_, position) => ({
      ...CARD.steps[0], result: { ...CARD.steps[0].result, key: 'step-' + position, phase: position >= 4 ? 'FINALLY' : 'MAIN',
        status: position === index ? 'RUNNING' : position < index ? 'SUCCESS' : 'PENDING' },
    })) };
  }

  function geometry(card: DashboardPipeCard): { track: HTMLElement; scroll: ReturnType<typeof vi.fn> } {
    setCards([card]);
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const track = fixture.nativeElement.querySelector('.pipe-track') as HTMLElement;
    Object.defineProperties(track, { clientWidth: { configurable: true, value: 600 }, scrollWidth: { configurable: true, value: 1565 } });
    vi.spyOn(track, 'getBoundingClientRect').mockReturnValue({ left: 0, right: 600, top: 0, bottom: 160, width: 600, height: 160 } as DOMRect);
    [...track.querySelectorAll<HTMLElement>('.pipe-step')].forEach((element, index) => {
      vi.spyOn(element, 'getBoundingClientRect').mockImplementation(() => ({
        left: index * 265 - track.scrollLeft, right: index * 265 + 240 - track.scrollLeft,
      } as DOMRect));
    });
    const scroll = vi.fn((options?: ScrollToOptions | number) => { track.scrollLeft = typeof options === 'number' ? options : options?.left ?? 0; });
    track.scrollTo = scroll;
    return { track, scroll };
  }
});

function pipeCard(id: number, name: string, outcome: PipeOutcome, stateChangedAt: string): DashboardPipeCard {
  return { ...CARD, id, name, lastOutcome: outcome, stateChangedAt,
    execution: { id, executionId: 'execution-' + id, status: 'COMPLETED', outcome, startedAt: stateChangedAt, finishedAt: stateChangedAt } as PipeExecution };
}
