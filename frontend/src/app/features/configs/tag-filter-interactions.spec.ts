import { Type, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
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

interface FilterHarness {
  loading: () => boolean;
  activeTab?: () => string;
  countsLoaded?: () => boolean;
  editorOpen: () => boolean;
  selectedTagIds: WritableSignal<readonly number[]>;
  tagMatchMode: WritableSignal<'AND' | 'OR'>;
  updatedAtSort: WritableSignal<'asc' | 'desc' | null>;
  pageIndex?: WritableSignal<number>;
  alertPage?: WritableSignal<number>;
  search?: WritableSignal<string>;
  appliedSearchTerm?: WritableSignal<string>;
  appliedValueSearchTerm?: WritableSignal<string>;
  selectedValueType?: WritableSignal<string>;
  templateFilterId?: WritableSignal<number | null>;
  pageSize: WritableSignal<number>;
  templatePage: WritableSignal<number>;
  templateListSearch: WritableSignal<string>;
  selectedTemplateTagKeys: WritableSignal<readonly string[]>;
  templateTagMatchMode: WritableSignal<'AND' | 'OR'>;
  visibleTemplates: () => readonly { id: number }[];
  addTagFilterById(tagId: number): void;
}

const resourceScreens: { name: string; component: Type<unknown>; endpoint: string; select: string }[] = [
  { name: 'alerts', component: AlertsComponent, endpoint: '/api/alerts', select: '#alerts-tag-filter' },
  { name: 'configs', component: ConfigsComponent, endpoint: '/api/configurations', select: '.tag-filter__select' },
  { name: 'secrets', component: SecretsComponent, endpoint: '/api/secrets', select: '#secrets-tag-filter' },
];
const templateScreens = [
  { name: 'alert templates', component: AlertsComponent, select: '#alert-templates-tag-filter', table: 'table[appResizableTable="alert-templates"]' },
  { name: 'procedure templates', component: ProceduresComponent, select: '#procedure-templates-tag-filter', table: 'table[appResizableTable="procedure-templates"]' },
];
const tags = [
  { id: 11, name: 'Same name', color: '#16A34A' },
  { id: 12, name: 'Same name', color: '#0EA5E9' },
  { id: 13, name: 'Another tag', color: '#7C3AED' },
];
const templateTags = [
  { nameKey: 'tags.first', color: '#16A34A' },
  { nameKey: 'tags.second', color: '#0EA5E9' },
  { nameKey: 'tags.third', color: '#7C3AED' },
];
const templates = [
  { id: 101, nameKey: 'Find first', tags: templateTags.slice(0, 1) },
  { id: 100, nameKey: 'Find both', tags: templateTags },
  { id: 104, nameKey: 'Find also both', tags: templateTags },
  { id: 102, nameKey: 'Find second', tags: templateTags.slice(1, 2) },
  { id: 103, nameKey: 'Unrelated', tags: templateTags },
].map((template, index) => ({
  ...template,
  templateKey: `example.Template${template.id}`,
  descriptionKey: 'Description',
  requiredCapability: 'STANDARD',
  parameters: [],
  outputs: [],
  alertCount: 10 - index,
  procedureCount: 0,
}));

describe('table tag filters', () => {
  let fixture: ComponentFixture<unknown>;
  let requests: { url: URL; method: string }[];

  beforeEach(() => {
    localStorage.clear();
    requests = [];
    vi.stubGlobal('ResizeObserver', class {
      observe(): void {}
      disconnect(): void {}
    });
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(input);
      requests.push({ url, method: init?.method ?? 'GET' });
      let body: unknown;
      if (['/api/alerts', '/api/configurations', '/api/secrets', '/api/procedures'].includes(url.pathname)) {
        body = {
          content: [{ id: 1, name: 'Find resource', templateId: 100, templateKey: 'example.Template100', templateNameKey: 'Find both', valueType: 'STRING', value: 'Example value', tags, parameters: [], usageCount: 0, usageAnalysisComplete: true, recoveryStatus: 'RECOVERABLE', updatedAt: '2026-01-01T00:00:00Z' }],
          page: { number: Number(url.searchParams.get('page')), totalElements: 100, totalPages: 10 },
        };
      } else if (url.pathname.endsWith('-tags')) body = { content: tags };
      else if (url.pathname.endsWith('-templates')) body = templates;
      else if (url.pathname.endsWith('/binding-options')) body = { configurations: [], secrets: [], procedures: [] };
      else if (url.pathname.endsWith('-executions')) body = { content: [], page: { number: 0, totalElements: 0, totalPages: 0 } };
      else if (url.pathname.endsWith('/expression-suggestions')) body = { secrets: [], configurations: [], environmentVariables: [], utilities: [], utilityFunctions: [] };
      else body = { maximumBytes: 1024 };
      return new Response(JSON.stringify(body), { status: 200 });
    }));
  });

  afterEach(() => {
    fixture?.destroy();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  async function open(component: Type<unknown>, tab: string): Promise<FilterHarness> {
    const parameters = convertToParamMap({ tab });
    await TestBed.configureTestingModule({
      imports: [component],
      providers: [
        { provide: AuthService, useValue: { getAccessToken: async () => 'test-token', userIdentifier: 'tag-filter-test-user' } },
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: parameters }, queryParamMap: of(parameters) } },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: 'https://example.test' } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key.startsWith('tags.') ? 'Same localized name' : key } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    await settle();
    const harness = fixture.componentInstance as FilterHarness;
    if (harness.countsLoaded) await vi.waitFor(() => expect(harness.countsLoaded!()).toBe(true));
    fixture.detectChanges();
    if (harness.activeTab) expect(harness.activeTab()).toBe(tab);
    return harness;
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await vi.waitFor(() => expect((fixture.componentInstance as FilterHarness).loading()).toBe(false));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rowTag(index: number, table = 'table'): HTMLButtonElement {
    return fixture.nativeElement.querySelectorAll(`${table} tbody .tag-list button.tag--filter`)[index];
  }

  function templateRowTag(table: string, templateId: number, nameKey: string): HTMLButtonElement {
    const template = templates.find((candidate) => candidate.id === templateId)!;
    const key: HTMLElement = fixture.nativeElement.querySelector(`${table} .template-key[title="${template.templateKey}"]`);
    expect(key, `visible template ${template.templateKey}`).not.toBeNull();
    const tagIndex = template.tags.findIndex((tag) => tag.nameKey === nameKey);
    expect(tagIndex).toBeGreaterThanOrEqual(0);
    return key.closest('tr')!.querySelectorAll<HTMLButtonElement>('.tag-list button.tag--filter')[tagIndex];
  }

  function assertNativeButton(button: HTMLButtonElement): void {
    expect(button).toBeDefined();
    expect(button.tagName).toBe('BUTTON');
    expect(button.type).toBe('button');
    expect(button.getAttribute('aria-label')).toContain(button.textContent!.trim());
    expect(button.title).toBe(button.getAttribute('aria-label'));
  }

  describe.each(resourceScreens)('$name resources', ({ name, component, endpoint, select }) => {
    it.each(['AND', 'OR'] as const)('accumulates exact IDs once and preserves %s and the other filters', async (mode) => {
      const harness = await open(component, name);
      const page = harness.pageIndex ?? harness.alertPage!;
      harness.selectedTagIds.set([11]);
      harness.tagMatchMode.set(mode);
      harness.updatedAtSort.set('asc');
      (harness.appliedSearchTerm ?? harness.search)!.set('find');
      harness.appliedValueSearchTerm?.set('value filter');
      harness.selectedValueType?.set('STRING');
      harness.templateFilterId?.set(100);
      page.set(3);
      fixture.detectChanges();

      const listRequests = () => requests.filter((request) => request.url.pathname === endpoint);
      const before = listRequests().length;
      const button = rowTag(1);
      assertNativeButton(button);
      const rowClick = vi.fn();
      button.closest('tr')!.addEventListener('click', rowClick);
      button.click();
      await settle();
      expect(rowClick).not.toHaveBeenCalled();
      expect(harness.editorOpen()).toBe(false);
      expect(harness.selectedTagIds()).toEqual([11, 12]);
      expect(listRequests()).toHaveLength(before + 1);
      const params = listRequests().at(-1)!.url.searchParams;
      expect(params.getAll('tagId')).toEqual(['11', '12']);
      expect(params.get('tagOperator')).toBe(mode);
      expect(params.get('page')).toBe('0');
      expect(params.get('sort')).toBe('updatedAt,asc');
      expect(params.get('name')).toBe(harness.appliedSearchTerm ? '~*find*' : 'find');
      if (harness.selectedValueType) expect(params.get('type')).toBe('STRING');
      if (harness.appliedValueSearchTerm) expect(params.get('valueContains')).toBe('value filter');
      if (harness.templateFilterId) expect(params.get('templateId')).toBe('100');

      page.set(3);
      fixture.detectChanges();
      rowTag(1).click();
      await settle();
      expect(harness.selectedTagIds()).toEqual([11, 12]);
      expect(page()).toBe(3);
      expect(listRequests()).toHaveLength(before + 1);

      const selector: HTMLSelectElement = fixture.nativeElement.querySelector(select);
      selector.value = '13';
      selector.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      expect(selector.value).toBe('');
      expect(harness.selectedTagIds()).toEqual([11, 12, 13]);
      expect(page()).toBe(0);
      expect(harness.tagMatchMode()).toBe(mode);
      expect(listRequests()).toHaveLength(before + 2);
      expect(requests.every((request) => request.method === 'GET')).toBe(true);
    });
  });

  it('retains configuration selector validation when delegating to the typed helper', async () => {
    const harness = await open(ConfigsComponent, 'configs');
    const before = requests.length;
    const selector: HTMLSelectElement = fixture.nativeElement.querySelector('.tag-filter__select');
    selector.dispatchEvent(new Event('change', { bubbles: true }));
    for (const invalidId of [NaN, 11.5, 999]) harness.addTagFilterById(invalidId);
    await settle();
    expect(harness.selectedTagIds()).toEqual([]);
    expect(requests).toHaveLength(before);
  });

  describe.each(templateScreens)('$name catalog', ({ component, select, table }) => {
    it.each(['AND', 'OR'] as const)('filters locally by exact key and preserves %s and search', async (mode) => {
      const harness = await open(component, 'templates');
      harness.selectedTemplateTagKeys.set(['tags.first']);
      harness.templateTagMatchMode.set(mode);
      harness.templateListSearch.set('find');
      harness.pageSize.set(1);
      harness.templatePage.set(1);
      fixture.detectChanges();
      const before = requests.length;
      const button = templateRowTag(table, 100, 'tags.second');
      assertNativeButton(button);
      const rowClick = vi.fn();
      button.closest('tr')!.addEventListener('click', rowClick);
      button.click();
      await settle();
      expect(rowClick).not.toHaveBeenCalled();
      expect(harness.editorOpen()).toBe(false);
      expect(harness.selectedTemplateTagKeys()).toEqual(['tags.first', 'tags.second']);
      expect(harness.templatePage()).toBe(0);
      expect(harness.templateListSearch()).toBe('find');
      expect(harness.templateTagMatchMode()).toBe(mode);
      expect(harness.visibleTemplates().map((template) => template.id)).toEqual(mode === 'AND' ? [100, 104] : [101, 100, 104, 102]);
      expect(requests).toHaveLength(before);

      harness.templatePage.set(1);
      fixture.detectChanges();
      templateRowTag(table, mode === 'AND' ? 104 : 100, 'tags.second').click();
      await settle();
      expect(harness.selectedTemplateTagKeys()).toEqual(['tags.first', 'tags.second']);
      expect(harness.templatePage()).toBe(1);
      expect(requests).toHaveLength(before);

      const selector: HTMLSelectElement = fixture.nativeElement.querySelector(select);
      selector.value = 'tags.third';
      selector.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      expect(selector.value).toBe('');
      expect(harness.selectedTemplateTagKeys()).toEqual(['tags.first', 'tags.second', 'tags.third']);
      expect(harness.templatePage()).toBe(0);
      expect(harness.templateListSearch()).toBe('find');
      expect(harness.templateTagMatchMode()).toBe(mode);
      expect(requests).toHaveLength(before);
    });
  });
});
