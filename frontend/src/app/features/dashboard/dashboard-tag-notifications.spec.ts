import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { NEVER } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AlertMessageService } from '../../core/alert-messages/alert-message.service';
import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { BrowserNotificationService } from '../../core/notifications/browser-notification.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { ViewerEventChannelService } from '../../core/realtime/viewer-event-channel.service';
import { DashboardAlertCard } from './dashboard-card';
import { DashboardLiveService } from './dashboard-live.service';
import { DashboardMuteService } from './dashboard-mute.service';
import { hasVisibleTags } from './dashboard-tags';
import { DashboardViewSettings, DEFAULT_VIEW_SETTINGS, storeViewSettings } from './dashboard-view-settings';
import { DashboardPipeLiveService } from './pipes/dashboard-pipe-live.service';
import { DashboardPipeCard } from './pipes/dashboard-pipe';

const ALERT: DashboardAlertCard = {
  alert: {
    id: 1, version: 1, templateId: 1, templateKey: 'example.Alert', templateNameKey: 'template',
    name: 'Alert', description: null, cronExpression: '-', enabled: true, allowConcurrentExecutions: false,
    executionMode: 'STANDALONE', smartExecutionEnabled: false, smartExecutionIntervalHours: null,
    smartExecutionPolicy: null, tags: [{ id: 3, version: 1, name: 'Shared', color: '#112233', scope: 'ALERT', createdAt: '', updatedAt: '' }],
    parameters: [], createdAt: '', updatedAt: '', persistentIssuesSince: null,
  },
  lastExecution: null, previousIssue: null, history: null, runningSince: null, historyWindowDays: 7, persistentIssues: null,
};
const PIPE: DashboardPipeCard = {
  id: 2, version: 1, name: 'Pipe', description: null, enabled: true, allowConcurrentExecutions: false,
  tags: [{ id: 12, version: 1, name: ' SHARED ', color: '#334455', scope: 'PIPE', createdAt: '', updatedAt: '' }], execution: null,
  lastOutcome: 'ERROR', stateChangedAt: null, steps: [], observedAt: '',
};

describe('dashboard notification tag agreement', () => {
  const alertNotification = vi.fn();
  const pipeNotification = vi.fn();

  beforeEach(() => {
    alertNotification.mockClear(); pipeNotification.mockClear();
    vi.spyOn(document, 'hasFocus').mockReturnValue(false);
    vi.stubGlobal('Notification', class {
      static permission = 'granted';
      constructor(title: string) { pipeNotification(title); }
    });
    const channel = { connectionState: signal('idle'), on: () => NEVER, start: vi.fn(), request: vi.fn() };
    TestBed.configureTestingModule({ providers: [
      DashboardLiveService, DashboardPipeLiveService,
      { provide: AuthService, useValue: { isAdmin: true } },
      { provide: AdminEventChannelService, useValue: channel },
      { provide: ViewerEventChannelService, useValue: channel },
      { provide: BrowserNotificationService, useValue: { notifyAlert: alertNotification } },
      { provide: DashboardMuteService, useValue: { isMuted: () => false } },
      { provide: Router, useValue: { url: '/alerts' } },
      { provide: LocalizationService, useValue: { translateDynamic: (key: string) => key } },
      { provide: AlertMessageService, useValue: {} },
    ] });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('alertify.dashboard.view');
    vi.restoreAllMocks(); vi.unstubAllGlobals();
  });

  it.each([
    [{ ...DEFAULT_VIEW_SETTINGS }, true, true],
    [{ ...DEFAULT_VIEW_SETTINGS, hiddenTagIds: [3] }, false, true],
    [{ ...DEFAULT_VIEW_SETTINGS, tagVisibilityByName: { shared: false } }, false, false],
    [{ ...DEFAULT_VIEW_SETTINGS, hiddenTagIds: [3, 12], tagVisibilityByName: { shared: true } }, true, true],
  ] as const)('matches both board filters for preferences %j', (settings, alertVisible, pipeVisible) => {
    storeViewSettings(settings);
    expect(hasVisibleTags(ALERT.alert.tags, settings)).toBe(alertVisible);
    expect(hasVisibleTags(PIPE.tags, settings)).toBe(pipeVisible);
    TestBed.inject(DashboardLiveService)['notify'](ALERT);
    TestBed.inject(DashboardPipeLiveService)['notify'](PIPE);
    expect(alertNotification).toHaveBeenCalledTimes(Number(alertVisible));
    expect(pipeNotification).toHaveBeenCalledTimes(Number(pipeVisible));
  });

  it('keeps OR and untagged notification behavior when the shared name is hidden', () => {
    const settings: DashboardViewSettings = { ...DEFAULT_VIEW_SETTINGS, tagVisibilityByName: { shared: false } };
    storeViewSettings(settings);
    TestBed.inject(DashboardLiveService)['notify']({ ...ALERT, alert: { ...ALERT.alert, tags: [] } });
    TestBed.inject(DashboardPipeLiveService)['notify']({ ...PIPE, tags: [...PIPE.tags, { ...PIPE.tags[0], id: 20, name: 'Other' }] });
    expect(alertNotification).toHaveBeenCalledTimes(1);
    expect(pipeNotification).toHaveBeenCalledTimes(1);
  });
});
