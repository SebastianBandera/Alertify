import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AlertMessageService } from '../../core/alert-messages/alert-message.service';
import { AlertApiService, AlertExecution } from '../../core/api/alert-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionActionsService } from '../../core/auth/session-actions.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { DashboardAcknowledgementService } from './dashboard-acknowledgement.service';
import { DashboardAlertCard } from './dashboard-card';
import { DashboardComponent } from './dashboard.component';
import { DashboardChange, DashboardLiveService } from './dashboard-live.service';
import { DashboardMuteService } from './dashboard-mute.service';
import { DashboardPipeLiveService } from './pipes/dashboard-pipe-live.service';
import { DashboardPipeAlertDetail } from './pipes/dashboard-pipes.component';

type Harness = {
  openPipeAlert: DashboardComponent['openPipeAlert'];
  closeCard: DashboardComponent['closeCard'];
  selectedCard: DashboardComponent['selectedCard'];
};

const EXECUTION: AlertExecution = {
  id: 7, executionId: 'step-execution', alertId: 42, alertName: 'Pipe alert', templateId: 1,
  templateNameKey: 'template', status: 'SUCCESS', trigger: 'PIPE', triggeredBy: 'operator',
  parentPipeExecutionId: 'pipe-execution', parentStepKey: 'check', parentHookInvocationId: null,
  parentHookName: null, closed: false, closureAt: null, closureBy: null, closureNote: null,
  startedAt: '2026-11-03T10:00:00Z', workStartedAt: '2026-11-03T10:00:00Z',
  finishedAt: '2026-11-03T10:00:01Z', durationMillis: 1000, idleMillis: 0, executionMillis: 1000,
  statusMessage: { statusCode: 200 }, errorType: null, errorMessage: null, workerName: 'worker',
  workerIpAddress: null, workerPort: null, workerInstanceId: null,
};
const CARD: DashboardAlertCard = {
  alert: {
    id: 42, version: 1, templateId: 1, templateKey: 'example.Alert', templateNameKey: 'template',
    name: 'Pipe alert', description: 'Definition', cronExpression: '-', enabled: true,
    allowConcurrentExecutions: false, executionMode: 'PIPE', smartExecutionEnabled: false,
    smartExecutionIntervalHours: null, smartExecutionPolicy: null, tags: [], parameters: [],
    createdAt: '', updatedAt: '', persistentIssuesSince: null,
  },
  lastExecution: EXECUTION, previousIssue: { ...EXECUTION, status: 'WARN', executionId: 'previous-issue' },
  history: { worstStatus: 'WARN', worstStatusLastAt: EXECUTION.finishedAt, currentStatusSince: EXECUTION.finishedAt },
  runningSince: null, historyWindowDays: 7, persistentIssues: null,
};
const DETAIL: DashboardPipeAlertDetail = {
  opener: null,
  step: {
    templateKey: CARD.alert.templateKey, alertExecution: EXECUTION,
    result: { key: 'check', position: 0, type: 'ALERT', phase: 'MAIN', resourceId: 42,
      resourceName: CARD.alert.name, status: 'SUCCESS', outcome: 'SUCCESS',
      resourceExecutionId: EXECUTION.executionId, startedAt: EXECUTION.startedAt,
      finishedAt: EXECUTION.finishedAt, errorCode: null },
  },
};

describe('dashboard alert detail from a Pipe', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let harness: Harness;
  let cards: ReturnType<typeof signal<DashboardAlertCard[]>>;
  let loading: ReturnType<typeof signal<boolean>>;
  let changes: Subject<DashboardChange>;

  beforeEach(async () => {
    cards = signal([CARD]); loading = signal(false); changes = new Subject();
    vi.stubGlobal('ResizeObserver', class { observe(): void { } disconnect(): void { } });
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: DashboardLiveService, useValue: { cards, loading, changes$: changes, totalCards: signal(1), loadedFromPages: signal(1), connectionState: signal('connected') } },
        { provide: DashboardPipeLiveService, useValue: { cards: signal([]) } },
        { provide: DashboardAcknowledgementService, useValue: { pending: () => null, load: vi.fn() } },
        { provide: DashboardMuteService, useValue: { isMuted: () => false, isIgnored: () => false } },
        { provide: AuthService, useValue: { isAdmin: true, canRunFromDashboard: true } },
        { provide: SessionActionsService, useValue: {} },
        { provide: AlertApiService, useValue: {} },
        { provide: AlertMessageService, useValue: {} },
        { provide: LocalizationService, useValue: { translate: (key: string) => key } },
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      ],
    }).overrideComponent(DashboardComponent, { set: { imports: [], template: '<div #grid></div>@if (selectedCard(); as card) { <section #detailDialog tabindex="-1">{{ card.lastExecution?.executionId }}</section> }' } }).compileComponents();
    fixture = TestBed.createComponent(DashboardComponent);
    harness = fixture.componentInstance as unknown as Harness;
    fixture.detectChanges(); await fixture.whenStable();
  });

  afterEach(() => {
    fixture.destroy(); vi.restoreAllMocks(); vi.unstubAllGlobals();
  });

  it('opens the complete card context even when PIPE mode excludes its standalone tile', async () => {
    harness.openPipeAlert(DETAIL);
    fixture.detectChanges(); await fixture.whenStable();
    expect(harness.selectedCard()?.alert).toBe(CARD.alert);
    expect(harness.selectedCard()?.lastExecution).toBe(EXECUTION);
    expect(harness.selectedCard()?.history).toBe(CARD.history);
  });

  it('keeps the selected step execution when a newer alert execution arrives', async () => {
    harness.openPipeAlert(DETAIL);
    fixture.detectChanges(); await fixture.whenStable();
    cards.set([{ ...CARD, lastExecution: { ...EXECUTION, executionId: 'newer-execution', status: 'ERROR' }, runningSince: '2026-11-03T11:00:00Z' }]);
    changes.next({ kind: 'update', alertId: 42, stateChanged: true });
    fixture.detectChanges(); await fixture.whenStable();
    expect(harness.selectedCard()?.lastExecution).toBe(EXECUTION);
    expect(harness.selectedCard()?.runningSince).toBeNull();
    expect(harness.selectedCard()?.previousIssue).toBeNull();
    expect(harness.selectedCard()?.history).toBeNull();
  });

  it('waits for alert metadata when the Pipe snapshot arrives first', async () => {
    loading.set(true); cards.set([]);
    harness.openPipeAlert(DETAIL);
    fixture.detectChanges(); await fixture.whenStable();
    expect(harness.selectedCard()).toBeNull();
    cards.set([CARD]); loading.set(false);
    fixture.detectChanges(); await fixture.whenStable();
    expect(harness.selectedCard()?.lastExecution).toBe(EXECUTION);
  });

  it('keeps the open detail stable when an unrelated alert updates', async () => {
    harness.openPipeAlert(DETAIL);
    fixture.detectChanges(); await fixture.whenStable();
    const selected = harness.selectedCard();
    cards.set([CARD, { ...CARD, alert: { ...CARD.alert, id: 43 } }]);
    fixture.detectChanges(); await fixture.whenStable();
    expect(harness.selectedCard()).toBe(selected);
  });

  it('returns keyboard focus to the step when the detail closes', async () => {
    const opener = document.createElement('button'); document.body.append(opener);
    try {
      harness.openPipeAlert({ ...DETAIL, opener });
      fixture.detectChanges(); await fixture.whenStable();
      harness.closeCard(); fixture.detectChanges();
      expect(harness.selectedCard()).toBeNull();
      expect(document.activeElement).toBe(opener);
    } finally { opener.remove(); }
  });
});
