import { Type, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { EMPTY, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { AlertsComponent } from '../alerts/alerts.component';
import { ProceduresComponent } from '../procedures/procedures.component';
import { SecretsComponent } from '../secrets/secrets.component';
import { ConfigsComponent } from './configs.component';

interface SortHarness {
  loading: () => boolean;
  pageIndex?: WritableSignal<number>;
  alertPage?: WritableSignal<number>;
  procedurePage?: WritableSignal<number>;
  search?: WritableSignal<string>;
  appliedSearchTerm?: WritableSignal<string>;
  selectedValueType?: WritableSignal<string>;
  selectedTagIds?: WritableSignal<readonly number[]>;
  tagMatchMode?: WritableSignal<string>;
  goToPage?(page: number): void;
  alertPageTo?(page: number): void;
  procedurePageTo?(page: number): void;
}

const screens: { name: string; component: Type<unknown>; endpoint: string; other: string }[] = [
  { name: 'configs', component: ConfigsComponent, endpoint: '/api/configurations', other: 'secrets' },
  { name: 'secrets', component: SecretsComponent, endpoint: '/api/secrets', other: 'configs' },
  { name: 'alerts', component: AlertsComponent, endpoint: '/api/alerts', other: 'procedures' },
  { name: 'procedures', component: ProceduresComponent, endpoint: '/api/procedures', other: 'alerts' },
];

describe.each(screens)('$name updated-at ordering', ({ name, component, endpoint, other }) => {
  let fixture: ComponentFixture<unknown>;
  let requests: URL[];
  const storageKey = `alertify.${name}.updated-at-sort`;
  const otherStorageKey = `alertify.${other}.updated-at-sort`;

  beforeEach(async () => {
    localStorage.clear();
    requests = [];
    vi.stubGlobal('ResizeObserver', class {
      observe(): void {}
      disconnect(): void {}
    });
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      const url = new URL(input);
      let body: unknown;
      if (url.pathname === endpoint) {
        requests.push(url);
        body = {
          content: [{ id: 1, name: 'Example', templateKey: 'example.TestTemplate', valueType: 'STRING', value: 'Example value', tags: [], parameters: [], usageCount: 0, recoveryStatus: 'RECOVERABLE', updatedAt: '2026-01-01T00:00:00Z' }],
          page: { number: Number(url.searchParams.get('page')), totalElements: 100, totalPages: 10 },
        };
      } else if (url.pathname.endsWith('-tags')) body = { content: [] };
      else if (url.pathname.endsWith('-templates')) body = [];
      else if (url.pathname.endsWith('/binding-options')) body = { configurations: [], secrets: [], procedures: [] };
      else if (url.pathname.endsWith('-executions')) body = { content: [], page: { number: 0, totalElements: 0, totalPages: 0 } };
      else if (url.pathname.endsWith('/expression-suggestions')) body = { secrets: [], configurations: [], environmentVariables: [], utilities: [], utilityFunctions: [] };
      else body = { maximumBytes: 1024 };
      return new Response(JSON.stringify(body), { status: 200 });
    }));
    await TestBed.configureTestingModule({
      imports: [component],
      providers: [
        { provide: AuthService, useValue: { getAccessToken: async () => 'test-token', userIdentifier: 'sort-test-user' } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ tab: name }) }, queryParamMap: of(convertToParamMap({ tab: name })) } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: 'https://example.test' } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
      ],
    }).compileComponents();
  });

  afterEach(() => {
    fixture?.destroy();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  async function open(): Promise<void> {
    fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    await settle();
  }

  async function settle(): Promise<void> {
    await vi.waitFor(() => expect((fixture.componentInstance as SortHarness).loading()).toBe(false));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function clickSort(): Promise<void> {
    fixture.nativeElement.querySelector('th.sortable-column button').click();
    await settle();
  }

  it('cycles newest, oldest and name ordering across pages while preserving filters', async () => {
    await open();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('updatedAt,desc');
    expect(fixture.nativeElement.querySelector('th.sortable-column').getAttribute('aria-sort')).toBe('descending');
    const harness = fixture.componentInstance as SortHarness;
    const page = harness.pageIndex ?? harness.alertPage ?? harness.procedurePage;
    const goToPage = harness.goToPage ?? harness.alertPageTo ?? harness.procedurePageTo;
    (harness.appliedSearchTerm ?? harness.search)!.set('find');
    harness.selectedValueType?.set('STRING');
    harness.selectedTagIds?.set([11, 12]);
    harness.tagMatchMode?.set('AND');
    page!.set(3);

    await clickSort();
    const params = requests.at(-1)!.searchParams;
    expect(params.get('sort')).toBe('updatedAt,asc');
    expect(params.get('page')).toBe('0');
    expect(params.get('name')).toBe(harness.appliedSearchTerm ? '~*find*' : 'find');
    if (harness.selectedValueType) expect(params.get('type')).toBe('STRING');
    if (harness.selectedTagIds) {
      expect(params.getAll('tagId')).toEqual(['11', '12']);
      expect(params.get('tagOperator')).toBe('AND');
    }
    expect(fixture.nativeElement.querySelector('th.sortable-column').getAttribute('aria-sort')).toBe('ascending');
    expect(localStorage.getItem(storageKey)).toBe('asc');

    goToPage!.call(harness, 1);
    await settle();
    expect(requests.at(-1)?.searchParams.get('page')).toBe('1');
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('updatedAt,asc');

    await clickSort();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('name,asc');
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
    expect(fixture.nativeElement.querySelector('th.sortable-column').getAttribute('aria-sort')).toBe('none');
    expect(localStorage.getItem(storageKey)).toBe('none');
    fixture.destroy();
    await open();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('name,asc');
    expect(fixture.nativeElement.querySelector('th.sortable-column').getAttribute('aria-sort')).toBe('none');

    await clickSort();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('updatedAt,desc');
    expect(localStorage.getItem(storageKey)).toBe('desc');
  });

  it.each(['asc', 'desc', 'none'])('restores its own %s preference independently of the other screen', async (sort) => {
    localStorage.setItem(storageKey, sort);
    localStorage.setItem(otherStorageKey, 'desc');
    await open();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe(sort === 'none' ? 'name,asc' : `updatedAt,${sort}`);
    expect(localStorage.getItem(otherStorageKey)).toBe('desc');
  });

  it('ignores an invalid stored sort', async () => {
    localStorage.setItem(storageKey, 'invalid');
    await open();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('updatedAt,desc');
  });

  it('keeps sorting when storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Storage unavailable'); });
    await open();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('updatedAt,desc');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage unavailable'); });
    await clickSort();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('updatedAt,asc');
    await clickSort();
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('name,asc');
  });
});
