import { TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { MAX_COLUMN_WIDTH, TableWidthPreferencesService } from './table-width-preferences.service';

describe('TableWidthPreferencesService', () => {
  const auth = { userIdentifier: 'monitoring:user-a' };
  const key = 'alertify.table-widths.v1.monitoring%3Auser-a.alerts';
  let service: TableWidthPreferencesService;

  beforeEach(() => {
    localStorage.clear();
    auth.userIdentifier = 'monitoring:user-a';
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: auth }] });
    service = TestBed.inject(TableWidthPreferencesService);
  });
  afterEach(() => vi.restoreAllMocks());

  it('persists a versioned map of dimensions and restores regardless of column order', () => {
    service.save('alerts', { name: 210.5, actions: 300 });
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual({ version: 1, widths: { name: 210.5, actions: 300 } });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: auth }] });
    expect(TestBed.inject(TableWidthPreferencesService).read('alerts', ['actions', 'name'])).toEqual({ name: 210.5, actions: 300 });
  });

  it('isolates tables and authenticated users', () => {
    service.save('alerts', { name: 200 });
    service.save('pipes', { name: 400 });
    auth.userIdentifier = 'monitoring:user-b';
    expect(service.read('alerts', ['name'])).toBeNull();
    service.save('alerts', { name: 700 });
    auth.userIdentifier = 'monitoring:user-a';
    expect(service.read('alerts', ['name'])).toEqual({ name: 200 });
    expect(service.read('pipes', ['name'])).toEqual({ name: 400 });
  });

  it.each([
    '{', 'null', '[]', '{"version":2,"widths":{"name":200}}',
    '{"version":1,"widths":{"name":"200"}}', '{"version":1,"widths":{"name":null}}',
    '{"version":1,"widths":{"name":63}}', '{"version":1,"widths":{"other":200}}',
    '{"version":1,"widths":{"name":200,"extra":200}}',
    JSON.stringify({ version: 1, widths: { name: MAX_COLUMN_WIDTH + 1 } }),
  ])('ignores malformed or incompatible storage: %s', stored => {
    localStorage.setItem(key, stored);
    expect(service.read('alerts', ['name'])).toBeNull();
  });

  it('rejects added, removed and duplicate column identifiers', () => {
    service.save('alerts', { name: 200, actions: 300 });
    expect(service.read('alerts', ['name'])).toBeNull();
    expect(service.read('alerts', ['name', 'actions', 'status'])).toBeNull();
    expect(service.read('alerts', ['name', 'name'])).toBeNull();
  });

  it('retains the latest failed write instead of an older stored value', () => {
    service.save('alerts', { name: 200 });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Unavailable'); });
    service.save('alerts', { name: 350 });
    expect(service.read('alerts', ['name'])).toEqual({ name: 350 });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Unavailable'); });
    expect(service.read('alerts', ['name'])).toEqual({ name: 350 });
    expect(service.read('pipes', ['name'])).toBeNull();
  });

  it('recovers from unavailable storage without sharing preferences with an unknown user', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Unavailable'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Unavailable'); });
    service.save('alerts', { name: 240 });
    expect(service.read('alerts', ['name'])).toEqual({ name: 240 });
    auth.userIdentifier = 'monitoring:unknown';
    service.save('alerts', { name: 500 });
    expect(service.read('alerts', ['name'])).toBeNull();
  });

  it('does not persist invalid dimensions and resets after clearing site storage', () => {
    for (const name of [NaN, Infinity, -1, 0, 63, MAX_COLUMN_WIDTH + 1]) service.save('alerts', { name });
    expect(localStorage.getItem(key)).toBeNull();
    service.save('alerts', { name: 200 });
    localStorage.clear();
    expect(service.read('alerts', ['name'])).toBeNull();
  });
});
