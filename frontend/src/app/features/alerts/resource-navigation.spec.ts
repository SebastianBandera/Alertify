import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { EMPTY } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { EditorDraftService } from '../../shared/editor-drafts/editor-draft.service';
import { HooksComponent } from '../hooks/hooks.component';
import { PipesComponent } from '../pipes/pipes.component';
import { ProceduresComponent } from '../procedures/procedures.component';
import { AlertsComponent } from './alerts.component';

interface ResourceHarness {
  countsLoaded(): boolean;
  loading(): boolean;
  editorOpen(): boolean;
  error(): string | null;
  form(): { name: string; executionMode?: 'STANDALONE' | 'PIPE'; [key: string]: unknown };
  patchForm(patch: { executionMode?: 'STANDALONE' | 'PIPE'; name?: string }): void;
  save(): Promise<void>;
  minimizeDraft(): void;
  restoreDraft(): void;
  selectTemplateId(id: number | null): void;
  search: WritableSignal<string>;
  templateListSearch: WritableSignal<string>;
  templateFilterId: WritableSignal<number | null>;
  selectedTagIds?: WritableSignal<readonly number[]>;
  selectedTemplateTagKeys: WritableSignal<readonly string[]>;
  templatePage: WritableSignal<number>;
  alertPage?: WritableSignal<number>;
  procedurePage?: WritableSignal<number>;
  pageIndex: WritableSignal<number>;
  activeTab(): string;
  historyPipeId(): number | null;
  changeTab(tab: 'pipes' | 'history'): Promise<void>;
  updateTemplateListSearch(value: string): void;
  applySearch(): void;
  closeEditor(): void;
}

const RESOURCE_NAME = 'QA María & backup #42';
const TEMPLATE_NAME = 'Monitor de página con Playwright';
const template = {
  id: 7, version: 0, templateKey: 'example.TestTemplate', nameKey: TEMPLATE_NAME, descriptionKey: 'Description',
  requiredCapability: 'STANDARD', tags: [], parameters: [], outputs: [], alertCount: 1, procedureCount: 1, sensitiveResult: false,
};
const resource = {
  id: 42, version: 1, templateId: 7, templateKey: template.templateKey, templateNameKey: TEMPLATE_NAME,
  name: RESOURCE_NAME, description: '', cronExpression: '-', enabled: false, allowConcurrentExecutions: false,
  executionMode: 'PIPE', smartExecutionEnabled: false, smartExecutionIntervalHours: null, smartExecutionPolicy: null,
  persistentIssuesSince: null, tags: [], parameters: [], createdAt: '', updatedAt: '',
};
const pipeResource = { id: 42, version: 1, name: RESOURCE_NAME, description: '', tags: [], enabled: false, allowConcurrentExecutions: false, finallyTimeout: null, steps: [] };
const screens = [
  { name: 'alerts', component: AlertsComponent, editKey: 'editAlertId' },
  { name: 'procedures', component: ProceduresComponent, editKey: 'editProcedureId' },
] as const;

describe('resource navigation', () => {
  let harness: RouterTestingHarness;
  let requests: URL[];
  let missingResource: boolean;
  let executionMode: 'STANDALONE' | 'PIPE' | undefined;
  let writes: Record<string, unknown>[];

  beforeEach(async () => {
    localStorage.clear();
    requests = [];
    missingResource = false;
    executionMode = 'PIPE';
    writes = [];
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.stubGlobal('ResizeObserver', class {
      observe(): void {}
      disconnect(): void {}
    });
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(input);
      requests.push(url);
      const path = url.pathname;
      let body: unknown = {};
      let status = 200;
      const page = (content: unknown[]) => ({ content, page: { number: Number(url.searchParams.get('page') ?? 0), totalPages: 10, totalElements: 100 } });
      if (init?.method === 'POST' || init?.method === 'PUT') {
        writes.push(JSON.parse(init.body as string));
        body = { ...resource, ...writes.at(-1) };
      } else if (path === '/api/pipes/42') {
        body = missingResource ? { code: 'RESOURCE_NOT_FOUND', message: 'Resource not found' } : pipeResource;
        status = missingResource ? 404 : 200;
      } else if (/\/api\/(alerts|procedures)\/42$/.test(path)) {
        body = missingResource ? { code: 'RESOURCE_NOT_FOUND', message: 'Resource not found' } : { ...resource, executionMode };
        status = missingResource ? 404 : 200;
      } else if (/\/api\/(alerts|procedures)$/.test(path)) {
        // The target is deliberately absent from the loaded page.
        body = page([{ ...resource, executionMode, id: 1, name: RESOURCE_NAME + ' similar' }]);
      } else if (path.endsWith('-templates')) {
        body = [template, { ...template, id: 8, templateKey: 'example.OtherTemplate', nameKey: 'Other', descriptionKey: 'Other' }];
      } else if (path.endsWith('/binding-options')) {
        body = { configurations: [], secrets: [], procedures: [], pipes: [] };
      } else if (path.endsWith('-tags')) {
        body = page([]);
      } else if (path === '/api/pipes/options') {
        body = { resources: [], expressionUtilityFunctions: [] };
      } else if (path === '/api/hooks/options') {
        body = { targets: [], secrets: [] };
      } else if (path === '/api/hooks') {
        body = page([{
          id: 1, publicId: 'example-hook', name: 'Example hook', tags: [], enabled: false, mode: 'PARALLEL',
          targets: ['ALERT', 'PROCEDURE', 'PIPE'].map((type, index) => ({ id: index + 1, type, resourceId: 42, resourceName: RESOURCE_NAME })),
        }]);
      } else if (path === '/api/pipes') {
        body = page([{
          id: 1, name: 'Example pipe', tags: [], enabled: false, steps: ['ALERT', 'PROCEDURE'].map((type, index) => ({
            id: index + 1, key: 'step-' + index, type, phase: 'MAIN', resourceId: 42,
            resourceName: RESOURCE_NAME, resourceTemplateKey: template.templateKey,
          })),
        }]);
      } else if (path.endsWith('-executions') || path === '/api/pipes/executions') {
        body = page([]);
      }
      return new Response(JSON.stringify(body), { status });
    }));
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'alerts', component: AlertsComponent },
          { path: 'procedures', component: ProceduresComponent },
          { path: 'pipes', component: PipesComponent },
          { path: 'hooks', component: HooksComponent },
        ]),
        provideLocationMocks(),
        { provide: AuthService, useValue: { userIdentifier: 'navigation-test-user', getAccessToken: async () => 'test-token' } },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: 'https://example.test' } },
        { provide: LocalizationService, useValue: { locale: signal('es-UY'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
      ],
    }).compileComponents();
    harness = await RouterTestingHarness.create();
    TestBed.inject(Router).setUpLocationChangeListener();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function current(): ResourceHarness {
    return harness.routeDebugElement!.componentInstance as ResourceHarness;
  }

  async function settle(): Promise<void> {
    await vi.waitFor(() => {
      expect(current().countsLoaded()).toBe(true);
      expect(current().loading()).toBe(false);
    });
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  async function navigate(url: string): Promise<void> {
    await harness.navigateByUrl(url);
    await settle();
  }

  for (const { name, editKey } of screens) {
    it.each(['STANDALONE', 'PIPE', undefined] as const)(`shows the ${name} run button only for standalone mode (%s)`, async (mode) => {
      executionMode = mode;
      await navigate('/' + name + '?tab=' + name);
      const count = mode === 'PIPE' ? 0 : 1;
      expect(harness.routeNativeElement!.querySelectorAll('.actions__run')).toHaveLength(count);
      expect(harness.routeNativeElement!.querySelectorAll('.actions__divider')).toHaveLength(count);
      expect(harness.routeNativeElement!.querySelectorAll('td.actions button:not(.actions__run)')).toHaveLength(3);
    });
    it(`links the ${name} template name to its filtered catalog and handles navigation in the same component`, async () => {
      await navigate('/' + name + '?tab=' + name);
      const instance = current();
      instance.selectedTemplateTagKeys.set(['unrelated']);
      instance.templatePage.set(3);
      const link = harness.routeNativeElement!.querySelector<HTMLAnchorElement>('td.template-cell a')!;
      expect(link.textContent).toBe(TEMPLATE_NAME);
      const destination = new URL(link.href);
      expect(destination.searchParams.get('templateSearch')).toBe(TEMPLATE_NAME);
      link.click();
      await vi.waitFor(() => expect(TestBed.inject(Router).url).toContain('tab=templates'));
      await settle();
      expect(current()).toBe(instance);
      expect(instance.templateListSearch()).toBe(TEMPLATE_NAME);
      expect(instance.selectedTemplateTagKeys()).toEqual([]);
      expect(instance.templatePage()).toBe(0);
      expect(harness.routeNativeElement!.querySelectorAll('tbody tr')).toHaveLength(1);

      instance.updateTemplateListSearch('');
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain('templateSearch='));
      await navigate('/' + name + '?tab=templates&templateSearch=' + encodeURIComponent(TEMPLATE_NAME));
      expect(instance.templateListSearch()).toBe(TEMPLATE_NAME);
      await navigate('/' + name + '?tab=templates');
      expect(instance.templateListSearch()).toBe('');
    });

    it(`loads the ${name} editor by ID while keeping the name filter after cancellation and reload`, async () => {
      localStorage.setItem('alertify.' + name + '.updated-at-sort', 'asc');
      await navigate('/' + name + '?tab=' + name + '&search=' + encodeURIComponent(RESOURCE_NAME) + '&' + editKey + '=42');
      await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
      expect(current().search()).toBe(RESOURCE_NAME);
      expect(current().form().name).toBe(RESOURCE_NAME);
      expect(requests.some((url) => url.pathname === '/api/' + name + '/42')).toBe(true);
      const list = requests.find((url) => url.pathname === '/api/' + name)!;
      expect(list.searchParams.get('name')).toBe(RESOURCE_NAME);
      expect(list.searchParams.get('page')).toBe('0');
      expect(list.searchParams.get('sort')).toBe('updatedAt,asc');
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain(editKey));
      current().closeEditor();
      expect(current().editorOpen()).toBe(false);
      expect(current().search()).toBe(RESOURCE_NAME);
      const consumedUrl = TestBed.inject(Router).url;
      await navigate('/pipes');
      await navigate(consumedUrl);
      expect(current().editorOpen()).toBe(false);
      expect(current().search()).toBe(RESOURCE_NAME);
      expect(requests.filter((url) => url.pathname === '/api/' + name + '/42')).toHaveLength(1);
    });

    it(`opens the ${name} editor on a query change and clears incompatible filters`, async () => {
      await navigate('/' + name + '?tab=' + name);
      const instance = current();
      instance.templateFilterId.set(999);
      instance.selectedTagIds?.set([999]);
      (instance.alertPage ?? instance.procedurePage)!.set(3);
      await navigate('/' + name + '?tab=' + name + '&search=' + encodeURIComponent(RESOURCE_NAME) + '&' + editKey + '=42');
      await vi.waitFor(() => expect(instance.editorOpen()).toBe(true));
      expect(current()).toBe(instance);
      expect(instance.templateFilterId()).toBeNull();
      expect(instance.selectedTagIds?.() ?? []).toEqual([]);
      expect((instance.alertPage ?? instance.procedurePage)!()).toBe(0);
    });

    it(`keeps the ${name} list usable when the linked resource was deleted`, async () => {
      missingResource = true;
      await navigate('/' + name + '?tab=' + name + '&search=' + encodeURIComponent(RESOURCE_NAME) + '&' + editKey + '=42');
      await vi.waitFor(() => expect(current().error()).toBeTruthy());
      expect(current().editorOpen()).toBe(false);
      expect(current().search()).toBe(RESOURCE_NAME);
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain(editKey));
    });

    it(`restores the ${name} search through browser back and forward and synchronizes manual changes`, async () => {
      await navigate('/' + name + '?tab=' + name + '&search=first');
      await navigate('/' + name + '?tab=' + name + '&search=second');
      const location = TestBed.inject(Location);
      location.back();
      await vi.waitFor(() => expect(current().search()).toBe('first'));
      location.forward();
      await vi.waitFor(() => expect(current().search()).toBe('second'));
      current().search.set(RESOURCE_NAME);
      current().applySearch();
      await vi.waitFor(() => expect(TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams['search']).toBe(RESOURCE_NAME));
      current().search.set('');
      current().applySearch();
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain('search='));
    });

    it(`does not load an invalid ${name} edit ID or reopen it after another query change`, async () => {
      await navigate('/' + name + '?tab=' + name + '&' + editKey + '=-42');
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain(editKey));
      expect(current().editorOpen()).toBe(false);
      expect(requests.some((url) => /\/-42$/.test(url.pathname))).toBe(false);
      await navigate('/' + name + '?tab=' + name + '&search=' + encodeURIComponent(RESOURCE_NAME) + '&' + editKey + '=42');
      await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain(editKey));
      current().closeEditor();
      await navigate('/' + name + '?tab=' + name + '&search=other');
      expect(current().editorOpen()).toBe(false);
      expect(requests.filter((url) => url.pathname === '/api/' + name + '/42')).toHaveLength(1);
    });

    it(`links a Pipe step to the ${name} editor with an encoded name and stable ID`, async () => {
      await navigate('/pipes');
      const links = harness.routeNativeElement!.querySelectorAll<HTMLAnchorElement>('.compact-list a');
      const link = links[name === 'alerts' ? 0 : 1];
      const destination = new URL(link.href);
      expect(destination.pathname).toBe('/' + name);
      expect(destination.searchParams.get('search')).toBe(RESOURCE_NAME);
      expect(destination.searchParams.get(editKey)).toBe('42');
      link.click();
      await vi.waitFor(() => expect(TestBed.inject(Router).url).toContain('/' + name));
      await settle();
      await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
      expect(current().form().name).toBe(RESOURCE_NAME);
    });
  }

  it('preserves the Procedure mode through editing, template selection, drafts and saving', async () => {
    await navigate('/procedures?tab=procedures&editProcedureId=42');
    await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
    expect(current().form().executionMode).toBe('PIPE');
    const select = harness.routeNativeElement!.querySelector<HTMLSelectElement>('select[name=executionMode]')!;
    expect(select.value).toBe('PIPE');
    current().minimizeDraft();
    current().restoreDraft();
    expect(current().form().executionMode).toBe('PIPE');
    current().selectTemplateId(7);
    expect(current().form().executionMode).toBe('PIPE');
    await current().save();
    expect(writes.at(-1)?.['executionMode']).toBe('PIPE');
    await navigate('/procedures?tab=procedures&editProcedureId=42');
    await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
    current().patchForm({ executionMode: 'STANDALONE' });
    await current().save();
    expect(writes.at(-1)?.['executionMode']).toBe('STANDALONE');
  });

  it('defaults legacy Procedure responses and drafts to standalone', async () => {
    executionMode = undefined;
    await navigate('/procedures?tab=procedures&editProcedureId=42');
    await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
    expect(current().form().executionMode).toBe('STANDALONE');
    const { executionMode: _mode, ...legacy } = current().form();
    TestBed.inject(EditorDraftService).save('procedures', legacy, null);
    current().restoreDraft();
    expect(current().form().executionMode).toBe('STANDALONE');
  });

  it('keeps the Pipe list run button available', async () => {
    await navigate('/pipes');
    expect(harness.routeNativeElement!.querySelectorAll('.actions__run')).toHaveLength(1);
  });

  for (const [index, name, editKey] of [[0, 'alerts', 'editAlertId'], [1, 'procedures', 'editProcedureId'], [2, 'pipes', 'pipeId']] as const) {
    it(`opens a Hook's ${name} destination filtered by name and edits the exact resource`, async () => {
      await navigate('/hooks?tab=hooks');
      const link = harness.routeNativeElement!.querySelectorAll<HTMLAnchorElement>('.compact-list a.resource-link')[index];
      const destination = new URL(link.href);
      expect(destination.pathname).toBe('/' + name);
      expect(destination.searchParams.get('tab')).toBe(name);
      expect(destination.searchParams.get('search')).toBe(RESOURCE_NAME);
      expect(destination.searchParams.get(editKey)).toBe('42');
      link.click();
      await vi.waitFor(() => expect(TestBed.inject(Router).url).toContain('/' + name));
      await settle();
      await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
      expect(current().form().name).toBe(RESOURCE_NAME);
      expect(current().search()).toBe(RESOURCE_NAME);
      expect(requests.some((url) => url.pathname === '/api/' + name + '/42')).toBe(true);
      await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain(editKey));
      current().closeEditor();
      expect(current().editorOpen()).toBe(false);
      expect(current().search()).toBe(RESOURCE_NAME);
    });
  }

  it('opens a Pipe from a query change, resets its page and does not reopen the consumed editor after reload', async () => {
    await navigate('/pipes');
    const instance = current();
    instance.pageIndex.set(3);
    await navigate('/pipes?tab=pipes&search=' + encodeURIComponent(RESOURCE_NAME) + '&pipeId=42');
    await vi.waitFor(() => expect(instance.editorOpen()).toBe(true));
    expect(current()).toBe(instance);
    expect(instance.pageIndex()).toBe(0);
    const list = requests.filter((url) => url.pathname === '/api/pipes').at(-1)!;
    expect(list.searchParams.get('page')).toBe('0');
    expect(list.searchParams.get('name')).toBe(RESOURCE_NAME);
    await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain('pipeId='));
    instance.closeEditor();
    const url = TestBed.inject(Router).url;
    await navigate('/hooks');
    await navigate(url);
    expect(current().editorOpen()).toBe(false);
    expect(current().search()).toBe(RESOURCE_NAME);
    expect(requests.filter((request) => request.pathname === '/api/pipes/42')).toHaveLength(1);
  });

  it('restores Pipe searches with browser back and forward and synchronizes manual changes', async () => {
    await navigate('/pipes?tab=pipes&search=first');
    await navigate('/pipes?tab=pipes&search=second');
    const location = TestBed.inject(Location);
    location.back();
    await vi.waitFor(() => expect(current().search()).toBe('first'));
    location.forward();
    await vi.waitFor(() => expect(current().search()).toBe('second'));
    current().search.set(RESOURCE_NAME);
    current().applySearch();
    await vi.waitFor(() => expect(TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams['search']).toBe(RESOURCE_NAME));
    current().search.set('');
    current().applySearch();
    await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain('search='));
  });

  it('preserves pipeId as a history filter without opening the Pipe editor', async () => {
    await navigate('/pipes?tab=history&pipeId=42');
    expect(current().activeTab()).toBe('history');
    expect(current().historyPipeId()).toBe(42);
    expect(current().editorOpen()).toBe(false);
    expect(TestBed.inject(Router).url).toContain('pipeId=42');
    expect(requests.some((url) => url.pathname === '/api/pipe-executions' && url.searchParams.get('pipeId') === '42')).toBe(true);
    expect(requests.some((url) => url.pathname === '/api/pipes/42')).toBe(false);
    await current().changeTab('pipes');
    await settle();
    expect(current().editorOpen()).toBe(false);
    expect(TestBed.inject(Router).url).not.toContain('pipeId=');
  });

  it('keeps the Pipe list filtered and reports an error for a deleted destination', async () => {
    missingResource = true;
    await navigate('/pipes?tab=pipes&search=' + encodeURIComponent(RESOURCE_NAME) + '&pipeId=42');
    await vi.waitFor(() => expect(current().error()).toBeTruthy());
    expect(current().editorOpen()).toBe(false);
    expect(current().search()).toBe(RESOURCE_NAME);
    await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain('pipeId='));
  });

  it('rejects an invalid Pipe editor ID and preserves legacy editor links without a search', async () => {
    await navigate('/pipes?pipeId=-42');
    await vi.waitFor(() => expect(TestBed.inject(Router).url).not.toContain('pipeId='));
    expect(current().editorOpen()).toBe(false);
    await navigate('/pipes?pipeId=42');
    await vi.waitFor(() => expect(current().editorOpen()).toBe(true));
    expect(current().form().name).toBe(RESOURCE_NAME);
    expect(current().search()).toBe('');
  });
});
