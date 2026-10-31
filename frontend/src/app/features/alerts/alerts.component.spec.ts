import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { EMPTY, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Alert, AlertApiService, AlertTemplate, AlertTemplateParameter } from '../../core/api/alert-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { EditorDraftService } from '../../shared/editor-drafts/editor-draft.service';
import { EditorViewportService } from '../../shared/editor-viewport/editor-viewport.service';
import { AlertsComponent } from './alerts.component';
import { PlaywrightWizardApiService, WizardSettings } from './playwright-wizard-api.service';

type Harness = {
  form: AlertsComponent['form'];
  formError: AlertsComponent['formError'];
  editorOpen: AlertsComponent['editorOpen'];
  openCreate: AlertsComponent['openCreate'];
  openEdit: AlertsComponent['openEdit'];
  preparePlaywrightAlert: AlertsComponent['preparePlaywrightAlert'];
  patchForm: AlertsComponent['patchForm'];
  selectTemplateId: AlertsComponent['selectTemplateId'];
  minimizeDraft: AlertsComponent['minimizeDraft'];
  restoreDraft: AlertsComponent['restoreDraft'];
  closeEditor: AlertsComponent['closeEditor'];
  save: AlertsComponent['save'];
  editPlaywrightPlan: AlertsComponent['editPlaywrightPlan'];
  returnFromPlaywright: AlertsComponent['returnFromPlaywright'];
  canEditPlaywrightPlan: AlertsComponent['canEditPlaywrightPlan'];
};

const DRAFT_ID = '557dd642-443e-407c-9ac7-7599e2d05375';
const STORAGE_KEY = 'alertify.editor-draft.v1.wizard-test-user.alerts';
const SETTINGS: WizardSettings = {
  url: 'https://example.test/', steps: 'QUERY #login', engine: 'chromium', browsers: ['chromium'],
  loadTimeoutSeconds: 10, elementTimeoutSeconds: 5, mode: 'ASK',
};
const PARAMETERS: readonly AlertTemplateParameter[] = [
  'url', 'steps', 'chromiumEnabled', 'firefoxEnabled', 'webkitEnabled', 'loadTimeoutSeconds', 'elementTimeoutSeconds',
].map((key, index) => ({
  id: index + 1, version: 0, key, labelKey: key, descriptionKey: key, javaType: 'java.lang.String',
  options: [], bindingAllowed: true, writableBindingRequired: false, defaultValue: null,
  multiline: key === 'steps', order: index, required: true, allowedSources: ['TEXT'],
  allowedConfigurationValueTypes: [], allowedSecretValueTypes: [], createdAt: '', updatedAt: '',
}));
const TEMPLATE: AlertTemplate = {
  id: 1, version: 0, templateKey: 'app.alertify.alerts.templates.PlaywrightPageAlertTemplate',
  nameKey: 'playwright', descriptionKey: 'playwright', requiredCapability: 'PLAYWRIGHT',
  persistentIssuesDefault: false, tags: [], alertCount: 0, parameters: PARAMETERS, createdAt: '', updatedAt: '',
};
const OTHER_TEMPLATE: AlertTemplate = { ...TEMPLATE, id: 2, templateKey: 'example.OtherAlertTemplate', parameters: [] };
const ALERT: Alert = {
  id: 42, version: 1, templateId: 2, templateKey: OTHER_TEMPLATE.templateKey, templateNameKey: 'other',
  name: 'Unrelated alert', description: '', cronExpression: '-', enabled: false, allowConcurrentExecutions: false,
  smartExecutionEnabled: false, smartExecutionIntervalHours: null, smartExecutionPolicy: null,
  persistentIssuesSince: null, tags: [], parameters: [], createdAt: '', updatedAt: '',
};

describe('Alert editor wizard draft ownership', () => {
  let fixture: ComponentFixture<AlertsComponent>;
  let harness: Harness;
  let drafts: EditorDraftService;
  const page = { content: [], page: { number: 0, totalPages: 0, totalElements: 0 } };
  const api = {
    listAlerts: vi.fn(), listTemplates: vi.fn(), listTags: vi.fn(), bindingOptions: vi.fn(), listExecutions: vi.fn(),
    createAlert: vi.fn(), updateAlert: vi.fn(), getAlert: vi.fn(),
  };
  const wizardRequest = vi.fn();
  const viewport = { capture: vi.fn(), restore: vi.fn() };

  async function configure(): Promise<void> {
    const queryParamMap = convertToParamMap({ tab: 'alerts' });
    await TestBed.configureTestingModule({
      imports: [AlertsComponent],
      providers: [
        { provide: AlertApiService, useValue: api },
        { provide: PlaywrightWizardApiService, useValue: { request: wizardRequest } },
        { provide: AuthService, useValue: { userIdentifier: 'wizard-test-user' } },
        { provide: LocalizationService, useValue: { locale: signal('en'), translate: (key: string) => key, translateDynamic: (key: string) => key } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap }, queryParamMap: of(queryParamMap) } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
      ],
    }).overrideComponent(AlertsComponent, {
      set: { template: '', providers: [{ provide: EditorViewportService, useValue: viewport }] },
    }).compileComponents();
    await createFixture();
    drafts = TestBed.inject(EditorDraftService);
  }

  async function createFixture(): Promise<void> {
    fixture = TestBed.createComponent(AlertsComponent);
    harness = fixture.componentInstance as unknown as Harness;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function prepare(): void {
    harness.preparePlaywrightAlert({ id: DRAFT_ID, settings: structuredClone(SETTINGS) });
    harness.patchForm({ name: 'Wizard alert' });
  }

  beforeEach(async () => {
    localStorage.removeItem(STORAGE_KEY);
    vi.resetAllMocks();
    api.listAlerts.mockResolvedValue(page);
    api.listTemplates.mockResolvedValue([TEMPLATE, OTHER_TEMPLATE]);
    api.listTags.mockResolvedValue([]);
    api.bindingOptions.mockResolvedValue({ configurations: [], secrets: [], procedures: [] });
    api.listExecutions.mockResolvedValue(page);
    api.createAlert.mockResolvedValue(ALERT);
    api.updateAlert.mockResolvedValue(ALERT);
    wizardRequest.mockResolvedValue({ deleted: true });
    await configure();
  });

  afterEach(() => {
    fixture.destroy();
    localStorage.removeItem(STORAGE_KEY);
  });

  it('sends a configuration interval without converting its missing fixed value to zero', async () => {
    harness.openEdit({ ...ALERT, smartExecutionEnabled: true, smartExecutionIntervalSource: 'CONFIGURATION', smartExecutionIntervalConfigurationId: 17, smartExecutionPolicy: 'ONCE_PER_INTERVAL' });
    await harness.save();
    expect(api.updateAlert).toHaveBeenCalledWith(ALERT.id, expect.objectContaining({
      smartExecutionIntervalSource: 'CONFIGURATION', smartExecutionIntervalConfigurationId: 17, smartExecutionIntervalHours: null,
    }));
    harness.patchForm({ smartExecutionIntervalSource: 'FIXED', smartExecutionIntervalHours: 6 });
    await harness.save();
    expect(api.updateAlert).toHaveBeenLastCalledWith(ALERT.id, expect.objectContaining({
      smartExecutionIntervalSource: 'FIXED', smartExecutionIntervalConfigurationId: null, smartExecutionIntervalHours: 6,
    }));
  });

  it('preserves the wizard draft when its form is cancelled and another alert is updated', async () => {
    prepare();
    harness.closeEditor();
    harness.openEdit(ALERT);
    await harness.save();

    expect(api.updateAlert).toHaveBeenCalledWith(ALERT.id, expect.objectContaining({ version: ALERT.version }));
    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it('discards the association when an existing alert is opened directly', async () => {
    prepare();
    harness.openEdit(ALERT);
    await harness.save();

    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it('restores the original association after minimization and component recreation', async () => {
    prepare();
    harness.minimizeDraft();
    fixture.destroy();
    await createFixture();
    harness.restoreDraft();
    await harness.save();

    expect(api.createAlert).toHaveBeenCalledTimes(1);
    expect(api.createAlert).toHaveBeenCalledWith(expect.objectContaining({
      parameters: expect.arrayContaining([expect.objectContaining({ parameterKey: 'steps', textValue: SETTINGS.steps })]),
    }));
    expect(api.createAlert.mock.calls[0][0]).not.toHaveProperty('wizardDraftId');
    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('DELETE', { id: DRAFT_ID });
  });

  it('persists only the association and keeps inline values excluded after a storage reload', async () => {
    prepare();
    harness.minimizeDraft();
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY)!);
    expect(stored.form.wizardDraftId).toBe(DRAFT_ID);
    expect(stored.form.parameters.steps.textValue).toBe('');
    expect(stored.form.parameters.steps.configured).toBe(false);
    fixture.destroy();
    TestBed.resetTestingModule();
    await configure();
    harness.restoreDraft();
    harness.minimizeDraft();

    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!).form.wizardDraftId).toBe(DRAFT_ID);
    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it('does not associate a manual creation with a previously prepared wizard', async () => {
    prepare();
    harness.openCreate(OTHER_TEMPLATE.id);
    harness.patchForm({ name: 'Manual alert' });
    await harness.save();

    expect(api.createAlert).toHaveBeenCalledTimes(1);
    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it('restores legacy drafts without inheriting the active wizard association', async () => {
    prepare();
    const legacyForm = { ...harness.form() };
    delete legacyForm.wizardDraftId;
    drafts.save('alerts', legacyForm, null);
    harness.restoreDraft();
    await harness.save();

    expect(api.createAlert).toHaveBeenCalledTimes(1);
    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it('ignores wizard metadata in a restored existing-alert draft', async () => {
    prepare();
    drafts.save('alerts', harness.form(), ALERT);
    harness.restoreDraft();
    await harness.save();

    expect(api.updateAlert).toHaveBeenCalledTimes(1);
    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it.each([OTHER_TEMPLATE.id, null])('preserves the wizard draft after selecting template %s', async templateId => {
    prepare();
    harness.selectTemplateId(templateId);
    if (templateId === null) harness.selectTemplateId(OTHER_TEMPLATE.id);
    await harness.save();

    expect(api.createAlert).toHaveBeenCalledTimes(1);
    expect(wizardRequest).not.toHaveBeenCalled();
  });

  it('retains the form and association on failure and deletes only after a successful retry', async () => {
    prepare();
    api.createAlert.mockRejectedValueOnce(new Error('Creation failed'));
    await harness.save();

    expect(harness.editorOpen()).toBe(true);
    expect(harness.formError()).toBe('Creation failed');
    expect(wizardRequest).not.toHaveBeenCalled();
    await harness.save();

    expect(api.createAlert).toHaveBeenCalledTimes(2);
    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('DELETE', { id: DRAFT_ID });
    expect(harness.editorOpen()).toBe(false);
  });

  it('keeps the alert creation successful when draft cleanup fails', async () => {
    prepare();
    wizardRequest.mockRejectedValueOnce(new Error('Cleanup failed'));
    await harness.save();

    expect(api.createAlert).toHaveBeenCalledTimes(1);
    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('DELETE', { id: DRAFT_ID });
    expect(harness.formError()).toBeNull();
    expect(harness.editorOpen()).toBe(false);
    expect(drafts.has('alerts')).toBe(false);
  });

  it('deletes the submitted draft only after creation succeeds, even if the editor association changes', async () => {
    prepare();
    let completeCreation!: () => void;
    api.createAlert.mockImplementationOnce(() => new Promise<void>(resolve => { completeCreation = resolve; }));
    const saving = harness.save();
    expect(wizardRequest).not.toHaveBeenCalled();
    harness.patchForm({ wizardDraftId: '744c4396-f2c9-4eb1-a43a-985caa82e78d' });
    completeCreation();
    await saving;

    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('DELETE', { id: DRAFT_ID });
  });

  function existing() {
    prepare();
    const parameters = structuredClone(harness.form().parameters);
    const alert = { ...ALERT, templateId: TEMPLATE.id, templateKey: TEMPLATE.templateKey, name: 'Existing Playwright', enabled: true };
    harness.openEdit(alert);
    harness.form.update(value => ({ ...value, parameters }));
    harness.patchForm({ name: 'Unsaved name', description: 'Preserve this', cronExpression: '0 * * * * *', enabled: true, tagIds: [7] });
    return alert;
  }

  it('offers the action only for an existing Playwright alert and imports the current unsaved form', async () => {
    harness.openEdit(ALERT);
    expect(harness.canEditPlaywrightPlan()).toBe(false);
    harness.openCreate(TEMPLATE.id);
    expect(harness.canEditPlaywrightPlan()).toBe(false);
    const alert = existing();
    expect(harness.canEditPlaywrightPlan()).toBe(true);
    const form = structuredClone(harness.form());
    wizardRequest.mockResolvedValue({ id: DRAFT_ID, document: { sourceAlert: { id: alert.id, version: alert.version, form } } });
    await harness.editPlaywrightPlan();
    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('CREATE_FROM_ALERT', { alertId: alert.id, version: alert.version, form });
    expect(harness.editorOpen()).toBe(false);
    expect(api.updateAlert).not.toHaveBeenCalled();
  });

  it('returns without applying changes and keeps the original pending form and the assistant draft', async () => {
    const alert = existing();
    const form = structuredClone(harness.form());
    wizardRequest.mockResolvedValue({ id: DRAFT_ID, document: { sourceAlert: { id: alert.id, version: alert.version, form } } });
    await harness.editPlaywrightPlan();
    await harness.returnFromPlaywright(null);
    expect(harness.editorOpen()).toBe(true);
    expect(harness.form()).toEqual(form);
    expect(api.updateAlert).not.toHaveBeenCalled();
    expect(wizardRequest.mock.calls.some(([operation]) => operation === 'DELETE')).toBe(false);
  });

  it('applies only direct plan fields, preserves bindings and metadata, and updates the same alert on Save', async () => {
    const alert = existing();
    harness.form.update(value => ({ ...value, parameters: { ...value.parameters, url: { ...value.parameters['url'], source: 'SECRET', textValue: '', secretId: 9 } } }));
    const form = structuredClone(harness.form());
    await harness.preparePlaywrightAlert({ id: DRAFT_ID, sourceAlert: { id: alert.id, version: alert.version, form: form as unknown as Record<string, unknown> }, parameterBindings: { url: { source: 'SECRET', id: 9, name: 'Private URL', version: 0 } }, settings: { ...SETTINGS, url: '', steps: 'QUERY #edited', browsers: ['firefox'] } });
    expect(harness.form()).toMatchObject({ name: 'Unsaved name', description: 'Preserve this', enabled: true, cronExpression: '0 * * * * *', tagIds: [7], parameters: { url: { source: 'SECRET', secretId: 9 }, steps: { textValue: 'QUERY #edited' }, firefoxEnabled: { textValue: 'true' } } });
    expect(api.updateAlert).not.toHaveBeenCalled();
    api.updateAlert.mockRejectedValueOnce(new Error('Version conflict'));
    await harness.save();
    expect(harness.editorOpen()).toBe(true);
    expect(harness.form().wizardDraftId).toBe(DRAFT_ID);
    expect(wizardRequest).not.toHaveBeenCalled();
    await harness.save();
    expect(api.createAlert).not.toHaveBeenCalled();
    expect(api.updateAlert).toHaveBeenLastCalledWith(alert.id, expect.objectContaining({ version: alert.version, enabled: true, parameters: expect.arrayContaining([expect.objectContaining({ parameterKey: 'url', source: 'SECRET', secretId: 9, textValue: null })]) }));
    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('DELETE', { id: DRAFT_ID });
  });

  it('restores an imported draft after recreation and keeps its original optimistic version', async () => {
    const alert = existing();
    const form = structuredClone(harness.form());
    fixture.destroy();
    await createFixture();
    api.getAlert.mockResolvedValue({ ...alert, version: 20 });
    await harness.preparePlaywrightAlert({ id: DRAFT_ID, settings: SETTINGS, sourceAlert: { id: alert.id, version: alert.version, form: form as unknown as Record<string, unknown> } });
    harness.minimizeDraft();
    harness.restoreDraft();
    await harness.save();
    expect(api.updateAlert).toHaveBeenCalledWith(alert.id, expect.objectContaining({ version: alert.version }));
    expect(wizardRequest).toHaveBeenCalledExactlyOnceWith('DELETE', { id: DRAFT_ID });
  });
});
