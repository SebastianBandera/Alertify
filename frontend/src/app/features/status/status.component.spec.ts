import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BackendStatus, SystemStatusApiService } from '../../core/api/system-status-api.service';
import { WorkerNodeStatus, WorkerStatusApiService } from '../../core/api/worker-status-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { StatusComponent } from './status.component';

const BACKEND: BackendStatus = {
  name: 'backend-test', instanceId: 'aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb',
  startedAt: '2026-11-07T12:00:00Z', sampledAt: '2026-11-07T12:01:00Z', uptimeMillis: 60_000,
  resourceUsage: { memoryUsedBytes: 128, memoryMaxBytes: 1024, heapUsedBytes: 64, heapMaxBytes: 512, cpuUsage: 0.25, availableProcessors: 4 },
};
const WORKER = {
  address: 'worker:9090', available: true, workerName: 'Worker', workerInstanceId: null,
  workerStartedAt: null, capabilities: ['STANDARD'], totalExecuted: 3, runningCount: 0, waitingCount: 0,
  maxConcurrentAlerts: 2, runningTasks: [], waitingTasks: [], totalExecutedProcedures: 1,
  runningProcedureCount: 0, runningProcedures: [], resourceUsage: null, error: null,
} as WorkerNodeStatus;
type Harness = { refresh(): void; selectTab(tab: 'live' | 'history'): void };

describe('Orchestrator backend live status', () => {
  let fixture: ComponentFixture<StatusComponent>;
  let harness: Harness;
  let visible: boolean;
  const system = { backend: vi.fn() };
  const workers = { status: vi.fn(), history: vi.fn() };

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.resetAllMocks();
    visible = true;
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visible ? 'visible' : 'hidden');
    system.backend.mockResolvedValue(BACKEND);
    workers.status.mockResolvedValue([WORKER]);
    workers.history.mockReturnValue(new Promise(() => {}));
    await TestBed.configureTestingModule({
      imports: [StatusComponent],
      providers: [
        { provide: SystemStatusApiService, useValue: system },
        { provide: WorkerStatusApiService, useValue: workers },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ tab: 'live' }) } } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
      ],
    }).compileComponents();
  });

  afterEach(() => {
    fixture?.destroy();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function settle(): Promise<void> {
    for (let i = 0; i < 8; i++) await Promise.resolve();
    fixture.detectChanges();
  }

  async function open(): Promise<void> {
    fixture = TestBed.createComponent(StatusComponent);
    harness = fixture.componentInstance as unknown as Harness;
    fixture.detectChanges();
    await settle();
  }

  it('renders identity, resources and uptime without counting the backend as a worker', async () => {
    await open();
    const card: HTMLElement = fixture.nativeElement.querySelector('.backend-card');
    expect(card.textContent).toContain('status.backend.title');
    expect(card.textContent).toContain(BACKEND.name);
    expect(card.textContent).toContain(BACKEND.instanceId);
    expect(card.textContent).toContain('25%');
    expect(card.textContent).toContain('1 min 0 s');
    expect(card.querySelectorAll('[role=meter]')).toHaveLength(2);
    expect(card.querySelector('.availability')?.textContent).toContain('status.available');
    expect(fixture.nativeElement.querySelector('.status-summary article strong').textContent.trim()).toBe('1');
    expect(fixture.nativeElement.querySelectorAll('.worker-grid .worker-card')).toHaveLength(1);
  });

  it('refreshes workers independently and never overlaps backend requests', async () => {
    let complete!: (status: BackendStatus) => void;
    system.backend.mockReturnValueOnce(new Promise((resolve) => { complete = resolve; }));
    await open();
    expect(fixture.nativeElement.querySelector('.backend-card').textContent).toContain('status.backend.loading');
    await vi.advanceTimersByTimeAsync(2000);
    harness.refresh();
    await settle();
    expect(system.backend).toHaveBeenCalledTimes(1);
    expect(workers.status.mock.calls.length).toBeGreaterThan(1);
    complete(BACKEND);
    await settle();
    expect(fixture.nativeElement.querySelector('.backend-card .availability').textContent).toContain('status.available');
  });

  it('marks old measurements stale after failure and recovers on manual refresh', async () => {
    await open();
    system.backend.mockRejectedValueOnce(new Error('private transport diagnostic'));
    harness.refresh();
    await settle();
    const card: HTMLElement = fixture.nativeElement.querySelector('.backend-card');
    expect(card.textContent).toContain('status.backend.offline');
    expect(card.textContent).toContain('status.backend.stale');
    expect(card.textContent).not.toContain('private transport diagnostic');
    expect(card.querySelector('.backend-card__stale')).not.toBeNull();
    expect(card.textContent).toContain(BACKEND.instanceId);
    harness.refresh();
    await settle();
    expect(card.querySelector('.availability')?.textContent).toContain('status.available');
    expect(card.querySelector('.backend-card__stale')).toBeNull();
  });

  it('times out after five seconds and ignores a late response', async () => {
    let complete!: (status: BackendStatus) => void;
    let requestSignal!: AbortSignal;
    system.backend.mockImplementation((signal: AbortSignal) => {
      requestSignal = signal;
      return new Promise((resolve) => { complete = resolve; });
    });
    await open();
    const firstSignal = requestSignal;
    const completeFirst = complete;
    await vi.advanceTimersByTimeAsync(4999);
    expect(firstSignal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(firstSignal.aborted).toBe(true);
    await settle();
    expect(fixture.nativeElement.querySelector('.backend-card').textContent).toContain('status.backend.unreachable');
    completeFirst(BACKEND);
    await settle();
    expect(fixture.nativeElement.querySelector('.backend-card code')).toBeNull();
  });

  it('aborts when hidden, resumes on return and stops backend polling in history', async () => {
    const signals: AbortSignal[] = [];
    system.backend.mockImplementation((signal: AbortSignal) => {
      signals.push(signal);
      return new Promise(() => {});
    });
    await open();
    visible = false;
    document.dispatchEvent(new Event('visibilitychange'));
    expect(signals[0].aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(6000);
    expect(system.backend).toHaveBeenCalledTimes(1);
    visible = true;
    document.dispatchEvent(new Event('visibilitychange'));
    await settle();
    expect(system.backend).toHaveBeenCalledTimes(2);
    harness.selectTab('history');
    expect(signals[1].aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(6000);
    expect(system.backend).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('.backend-card')).toBeNull();
    harness.selectTab('live');
    await settle();
    expect(system.backend).toHaveBeenCalledTimes(3);
    fixture.destroy();
    expect(signals[2].aborted).toBe(true);
  });

  it('distinguishes unavailable measurements from measured zero', async () => {
    system.backend.mockResolvedValue({ ...BACKEND, resourceUsage: { ...BACKEND.resourceUsage, memoryUsedBytes: null, memoryMaxBytes: null, heapUsedBytes: null, heapMaxBytes: null, cpuUsage: null } });
    await open();
    const card: HTMLElement = fixture.nativeElement.querySelector('.backend-card');
    expect(card.textContent).toContain('status.resourceUnknown');
    expect(card.querySelector('[role=meter]')).toBeNull();
    system.backend.mockResolvedValue({ ...BACKEND, resourceUsage: { ...BACKEND.resourceUsage, memoryUsedBytes: 0, cpuUsage: 0 } });
    harness.refresh();
    await settle();
    expect(card.textContent).toContain('0 B');
    expect(card.textContent).toContain('0%');
    expect(card.querySelectorAll('[role=meter]')).toHaveLength(2);
  });
});
