import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocalizationService } from '../../core/i18n/localization.service';
import { DashboardTimeService } from './dashboard-time.service';

const NOW = Date.parse('2026-11-15T12:00:00Z');

describe('dashboard shared relative time', () => {
  const locale = signal<'en' | 'es-UY'>('en');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    locale.set('en');
    TestBed.configureTestingModule({ providers: [
      DashboardTimeService,
      { provide: LocalizationService, useValue: { locale } },
    ] });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it.each([
    [0, '0 seconds ago'],
    [59, '59 seconds ago'],
    [60, '1 minute ago'],
    [3599, '59 minutes ago'],
    [3600, '1 hour ago'],
    [86399, '23 hours ago'],
    [86400, '1 day ago'],
    [172800, '2 days ago'],
  ])('formats an age of %s seconds as %s', (seconds, expected) => {
    const time = TestBed.inject(DashboardTimeService);
    expect(time.relativeTime(new Date(NOW - seconds * 1000).toISOString())).toBe(expected);
  });

  it('uses the active locale and changes it without recreating the view', () => {
    const time = TestBed.inject(DashboardTimeService);
    const finishedAt = new Date(NOW - 2 * 3600 * 1000).toISOString();
    expect(time.relativeTime(finishedAt)).toBe('2 hours ago');
    locale.set('es-UY');
    expect(time.relativeTime(finishedAt)).toBe('hace 2 horas');
  });

  it('does not report a negative age when the server clock is ahead', () => {
    expect(TestBed.inject(DashboardTimeService).relativeTime(new Date(NOW + 3600 * 1000).toISOString())).toBe('0 seconds ago');
  });

  it('advances across the minute boundary without a live card event', () => {
    const time = TestBed.inject(DashboardTimeService);
    const finishedAt = new Date(NOW - 59_000).toISOString();
    expect(time.relativeTime(finishedAt)).toBe('59 seconds ago');
    vi.advanceTimersByTime(1_000);
    expect(time.relativeTime(finishedAt)).toBe('1 minute ago');
  });

  it('uses one timer for multiple card reads and releases it with its view scope', () => {
    const time = TestBed.inject(DashboardTimeService);
    for (let card = 0; card < 20; card++) time.relativeTime(new Date(NOW - card * 1000).toISOString());
    expect(vi.getTimerCount()).toBe(1);
    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
  });
});
