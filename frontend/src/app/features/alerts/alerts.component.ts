import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import {
  Alert,
  AlertApiService,
  AlertBindingOptions,
  AlertExecution,
  AlertExecutionStatus,
  AlertImportResult,
  AlertParameterSource,
  AlertParameterWriteRequest,
  AlertTemplate,
  AlertTemplateParameter,
  AlertTag,
} from '../../core/api/alert-api.service';
import { ApiRequestError, TagMatchMode } from '../../core/api/configuration-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

type AlertTab = 'alerts' | 'templates' | 'history';
type AlertFormField = 'template' | 'name' | 'cron';
type ParameterFormSource = AlertParameterSource | 'OPTION';
type AlertFormErrors = Partial<Record<AlertFormField, string>>;

interface ParameterForm {
  configured: boolean;
  source: ParameterFormSource;
  textValue: string;
  configurationId: number | null;
  secretId: number | null;
}

interface AlertForm {
  templateId: number | null;
  name: string;
  description: string;
  cronExpression: string;
  enabled: boolean;
  allowConcurrentExecutions: boolean;
  tagIds: number[];
  parameters: Readonly<Record<string, ParameterForm>>;
}

interface TagForm {
  name: string;
  color: string;
}

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250, 500, 1000] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.alerts.page-size';
const EMPTY_BINDINGS: AlertBindingOptions = { configurations: [], secrets: [] };

function readStoredPageSize(): number {
  try {
    const storedValue = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return PAGE_SIZE_OPTIONS.some((pageSize) => pageSize === storedValue) ? storedValue : 10;
  } catch {
    return 10;
  }
}

function alertTab(value: string | null): AlertTab {
  if (value === 'templates' || value === 'history') return value;
  return 'alerts';
}

@Component({
  selector: 'app-alerts',
  imports: [DatePipe, FormsModule],
  templateUrl: './alerts.component.html',
  styleUrl: './alerts.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly api = inject(AlertApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly elementRef: ElementRef<HTMLElement> = inject(ElementRef);

  protected readonly activeTab = signal<AlertTab>('alerts');
  protected readonly alerts = signal<readonly Alert[]>([]);
  protected readonly templates = signal<readonly AlertTemplate[]>([]);
  protected readonly tags = signal<readonly AlertTag[]>([]);
  protected readonly executions = signal<readonly AlertExecution[]>([]);
  protected readonly bindings = signal<AlertBindingOptions>(EMPTY_BINDINGS);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly exporting = signal(false);
  protected readonly importing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly formFieldErrors = signal<AlertFormErrors>({});
  protected readonly search = signal('');
  protected readonly templateFilterId = signal<number | null>(null);
  protected readonly selectedTagIds = signal<readonly number[]>([]);
  protected readonly tagMatchMode = signal<TagMatchMode>('OR');
  protected readonly pageSize = signal(readStoredPageSize());
  protected readonly alertPage = signal(0);
  protected readonly alertTotalPages = signal(0);
  protected readonly alertTotalElements = signal(0);
  protected readonly historyPage = signal(0);
  protected readonly historyTotalPages = signal(0);
  protected readonly historyTotalElements = signal(0);
  protected readonly templatePage = signal(0);
  protected readonly sortedTemplates = computed(() =>
    [...this.templates()].sort((first, second) =>
      second.alertCount - first.alertCount
      || this.dynamic(first.nameKey).localeCompare(
        this.dynamic(second.nameKey),
        this.localization.locale(),
        { sensitivity: 'base' },
      ),
    ),
  );
  protected readonly templateTotalPages = computed(() =>
    Math.ceil(this.sortedTemplates().length / this.pageSize()),
  );
  protected readonly pagedTemplates = computed(() => {
    const start = this.templatePage() * this.pageSize();
    return this.sortedTemplates().slice(start, start + this.pageSize());
  });
  protected readonly historyAlertId = signal<number | null>(null);
  protected readonly historyStatus = signal<AlertExecutionStatus | ''>('');
  protected readonly editorOpen = signal(false);
  protected readonly editingAlert = signal<Alert | null>(null);
  protected readonly form = signal<AlertForm>(this.emptyForm());
  protected readonly templateSearch = signal('');
  protected readonly templatePickerOpen = signal(false);
  protected readonly selectedTemplate = computed(() =>
    this.templates().find((template) => template.id === this.form().templateId) ?? null,
  );
  protected readonly filteredTemplates = computed(() => {
    const query = this.templateSearch().trim().toLocaleLowerCase();
    if (!query) return this.sortedTemplates();
    return this.sortedTemplates().filter((template) =>
      this.dynamic(template.nameKey).toLocaleLowerCase().includes(query)
      || this.dynamic(template.descriptionKey).toLocaleLowerCase().includes(query)
      || template.templateKey.toLocaleLowerCase().includes(query),
    );
  });
  protected readonly selectedFilterTags = computed(() => {
    const tagsById = new Map(this.tags().map((tag) => [tag.id, tag]));
    return this.selectedTagIds().flatMap((id) => {
      const tag = tagsById.get(id);
      return tag ? [tag] : [];
    });
  });
  protected readonly availableFilterTags = computed(() => {
    const selected = new Set(this.selectedTagIds());
    return this.tags().filter((tag) => !selected.has(tag.id));
  });
  protected readonly tagDialogOpen = signal(false);
  protected readonly editingTag = signal<AlertTag | null>(null);
  protected readonly tagForm = signal<TagForm>({ name: '', color: '#6D5DFC' });
  protected readonly tagError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const requestedTab = this.route.snapshot.queryParamMap.get('tab');
    this.activeTab.set(alertTab(requestedTab));
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((parameters) => {
        const tab = alertTab(parameters.get('tab'));
        if (tab === this.activeTab()) return;

        this.activeTab.set(tab);
        void this.loadTab(tab);
      });
    if (requestedTab !== this.activeTab()) {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab: this.activeTab() },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    }

    await Promise.all([this.loadAlerts(), this.loadTemplates(), this.loadTags(), this.loadBindings(), this.loadHistory()]);
  }

  protected async selectTab(tab: AlertTab): Promise<void> {
    this.activeTab.set(tab);
    await Promise.all([
      this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab },
        queryParamsHandling: 'merge',
      }),
      this.loadTab(tab),
    ]);
  }

  private async loadTab(tab: AlertTab): Promise<void> {
    if (tab === 'alerts') await this.loadAlerts();
    if (tab === 'templates') await this.loadTemplates();
    if (tab === 'history') await this.loadHistory();
  }

  protected async loadAlerts(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.listAlerts(
        this.search(), this.templateFilterId(), this.selectedTagIds(), this.tagMatchMode(),
        this.alertPage(), this.pageSize(),
      );
      this.alerts.set(page.content);
      this.alertPage.set(page.page.number);
      this.alertTotalPages.set(page.page.totalPages);
      this.alertTotalElements.set(page.page.totalElements);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadTags(): Promise<void> {
    try {
      this.tags.set(await this.api.listTags());
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected async exportAlerts(): Promise<void> {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const blob = await this.api.exportAlerts();
      const url = URL.createObjectURL(blob);
      try {
        const link = document.createElement('a');
        link.href = url;
        link.download = 'alertify-alerts.csv';
        document.body.appendChild(link);
        link.click();
        link.remove();
      } finally {
        URL.revokeObjectURL(url);
      }
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.exporting.set(false);
    }
  }

  protected chooseImportFile(fileInput: HTMLInputElement): void {
    if (this.importing()) return;
    fileInput.value = '';
    fileInput.click();
  }

  protected async importAlerts(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0);
    if (!file) return;
    if (!window.confirm(this.localization.translate('alerts.importConfirm'))) {
      input.value = '';
      return;
    }

    this.importing.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const result = await this.api.importAlerts(file);
      this.notice.set(this.importSuccessMessage(result));
      this.alertPage.set(0);
      await Promise.all([this.loadAlerts(), this.loadTags()]);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      input.value = '';
      this.importing.set(false);
    }
  }

  private importSuccessMessage(result: AlertImportResult): string {
    return this.localization.translate('alerts.importSuccess')
      .replace('{created}', String(result.created))
      .replace('{updated}', String(result.updated))
      .replace('{unchanged}', String(result.unchanged))
      .replace('{tagsCreated}', String(result.tagsCreated));
  }

  protected async loadTemplates(): Promise<void> {
    try {
      this.templates.set(await this.api.listTemplates());
      if (this.templatePage() >= this.templateTotalPages())
        this.templatePage.set(Math.max(0, this.templateTotalPages() - 1));
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected async loadBindings(): Promise<void> {
    try {
      this.bindings.set(await this.api.bindingOptions());
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected async loadHistory(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.listExecutions(
        this.historyAlertId(), this.historyStatus(), this.historyPage(), this.pageSize(),
      );
      this.executions.set(page.content);
      this.historyPage.set(page.page.number);
      this.historyTotalPages.set(page.page.totalPages);
      this.historyTotalElements.set(page.page.totalElements);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected applySearch(): void {
    this.alertPage.set(0);
    void this.loadAlerts();
  }

  protected addTagFilter(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const tagId = Number(select.value);
    if (tagId && !this.selectedTagIds().includes(tagId)) {
      this.selectedTagIds.set([...this.selectedTagIds(), tagId]);
      this.alertPage.set(0);
      void this.loadAlerts();
    }
    select.value = '';
  }

  protected removeTagFilter(tagId: number): void {
    this.selectedTagIds.set(this.selectedTagIds().filter((id) => id !== tagId));
    this.alertPage.set(0);
    void this.loadAlerts();
  }

  protected updateTagMatchMode(mode: TagMatchMode): void {
    this.tagMatchMode.set(mode);
    this.alertPage.set(0);
    void this.loadAlerts();
  }

  protected showTemplateAlerts(template: AlertTemplate): void {
    this.search.set('');
    this.templateFilterId.set(template.id);
    this.alertPage.set(0);
    void this.selectTab('alerts');
  }

  protected applyHistoryFilters(): void {
    this.historyPage.set(0);
    void this.loadHistory();
  }

  protected updateHistoryAlertFilter(alertId: number | null): void {
    this.historyAlertId.set(alertId);
    this.applyHistoryFilters();
  }

  protected updateHistoryStatusFilter(status: AlertExecutionStatus | ''): void {
    this.historyStatus.set(status);
    this.applyHistoryFilters();
  }

  protected updatePageSize(value: string | number): void {
    const pageSize = Number(value);
    if (!PAGE_SIZE_OPTIONS.some((option) => option === pageSize)) return;

    this.pageSize.set(pageSize);
    this.alertPage.set(0);
    this.templatePage.set(0);
    this.historyPage.set(0);
    try {
      localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(pageSize));
    } catch {
      // The selection still applies to this page when browser storage is unavailable.
    }

    if (this.activeTab() === 'alerts') void this.loadAlerts();
    if (this.activeTab() === 'history') void this.loadHistory();
  }

  protected openCreate(templateId?: number): void {
    this.editingAlert.set(null);
    const template = templateId === undefined
      ? null
      : this.templates().find((item) => item.id === templateId) ?? null;
    this.form.set(this.formForTemplate(template));
    this.templateSearch.set(template ? this.dynamic(template.nameKey) : '');
    this.templatePickerOpen.set(false);
    this.formError.set(null);
    this.formFieldErrors.set({});
    this.editorOpen.set(true);
  }

  protected openEdit(alert: Alert): void {
    const template = this.templates().find((item) => item.id === alert.templateId) ?? null;
    const form = this.formForTemplate(template);
    const parameters = { ...form.parameters };
    for (const value of alert.parameters) {
      const definition = template?.parameters.find((parameter) => parameter.key === value.parameterKey);
      parameters[value.parameterKey] = {
        configured: true,
        source: value.source === 'TEXT' && definition?.options.includes(value.textValue ?? '')
          ? 'OPTION'
          : value.source,
        textValue: value.textValue ?? '',
        configurationId: value.configurationId,
        secretId: value.secretId,
      };
    }
    this.editingAlert.set(alert);
    this.form.set({
      ...form,
      name: alert.name,
      description: alert.description ?? '',
      cronExpression: alert.cronExpression,
      enabled: alert.enabled,
      allowConcurrentExecutions: alert.allowConcurrentExecutions,
      tagIds: alert.tags.map((tag) => tag.id),
      parameters,
    });
    this.templateSearch.set(template ? this.dynamic(template.nameKey) : alert.templateKey);
    this.templatePickerOpen.set(false);
    this.formError.set(null);
    this.formFieldErrors.set({});
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) {
      this.editorOpen.set(false);
      this.templatePickerOpen.set(false);
    }
  }

  protected selectTemplate(template: AlertTemplate): void {
    const current = this.form();
    this.form.set({
      ...this.formForTemplate(template),
      name: current.name,
      description: current.description,
      cronExpression: current.cronExpression,
      enabled: current.enabled,
      allowConcurrentExecutions: current.allowConcurrentExecutions,
      tagIds: current.tagIds,
    });
    this.templateSearch.set(this.dynamic(template.nameKey));
    this.templatePickerOpen.set(false);
    this.clearFieldError('template');
  }

  protected updateTemplateSearch(value: string): void {
    this.templateSearch.set(value);
    this.templatePickerOpen.set(true);
    this.clearFieldError('template');
    const selected = this.selectedTemplate();
    if (selected && value !== this.dynamic(selected.nameKey)) {
      this.form.update((form) => ({ ...form, templateId: null, parameters: {} }));
    }
  }

  protected patchForm(patch: Partial<Omit<AlertForm, 'parameters'>>): void {
    this.form.update((form) => ({ ...form, ...patch }));
    if (patch.name !== undefined) this.clearFieldError('name');
    if (patch.cronExpression !== undefined) this.clearFieldError('cron');
  }

  protected patchParameter(key: string, patch: Partial<ParameterForm>): void {
    this.form.update((form) => ({
      ...form,
      parameters: { ...form.parameters, [key]: { ...form.parameters[key], ...patch } },
    }));
  }

  protected toggleFormTag(tagId: number, checked: boolean): void {
    this.form.update((form) => ({
      ...form,
      tagIds: checked ? [...form.tagIds, tagId] : form.tagIds.filter((id) => id !== tagId),
    }));
  }

  protected async save(): Promise<void> {
    const form = this.form();
    const template = this.selectedTemplate();
    const editing = this.editingAlert();
    const validationErrors: AlertFormErrors = {};
    if (!template)
      validationErrors.template = this.localization.translate('alerts.form.templateRequired');
    if (!form.name.trim())
      validationErrors.name = this.localization.translate('alerts.form.nameRequired');
    if (!form.cronExpression.trim())
      validationErrors.cron = this.localization.translate('alerts.form.cronRequired');
    if (Object.keys(validationErrors).length) {
      this.showFieldErrors(validationErrors);
      return;
    }
    if (!template) return;

    const parameters: AlertParameterWriteRequest[] = [];
    for (const definition of template.parameters) {
      const value = form.parameters[definition.key];
      if (!value?.configured) continue;
      const source: AlertParameterSource = value.source === 'OPTION' ? 'TEXT' : value.source;
      parameters.push({
        parameterKey: definition.key,
        source,
        textValue: source === 'TEXT' ? value.textValue : null,
        configurationId: source === 'CONFIGURATION' ? value.configurationId : null,
        secretId: source === 'SECRET' ? value.secretId : null,
      });
    }

    this.saving.set(true);
    this.formError.set(null);
    this.formFieldErrors.set({});
    try {
      const request = {
        ...(editing ? { version: editing.version } : { templateId: template.id }),
        name: form.name.trim(),
        description: form.description.trim() || null,
        cronExpression: form.cronExpression.trim(),
        enabled: form.enabled,
        allowConcurrentExecutions: form.allowConcurrentExecutions,
        tagIds: form.tagIds,
        parameters,
      };
      if (editing) await this.api.updateAlert(editing.id, request);
      else await this.api.createAlert(request);
      this.editorOpen.set(false);
      await Promise.all([this.loadAlerts(), this.loadTemplates()]);
    } catch (error) {
      const fieldErrors = this.alertFieldErrors(error);
      if (Object.keys(fieldErrors).length) this.showFieldErrors(fieldErrors);
      else this.formError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteAlert(alert: Alert): Promise<void> {
    if (!window.confirm(this.localization.translate('alerts.deleteConfirm'))) return;
    this.error.set(null);
    try {
      await this.api.deleteAlert(alert.id, alert.version);
      await Promise.all([this.loadAlerts(), this.loadTemplates()]);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected openTagManager(): void {
    this.editingTag.set(null);
    this.tagForm.set({ name: '', color: '#6D5DFC' });
    this.tagError.set(null);
    this.tagDialogOpen.set(true);
  }

  protected closeTagManager(): void {
    if (!this.saving()) this.tagDialogOpen.set(false);
  }

  protected editTag(tag: AlertTag): void {
    this.editingTag.set(tag);
    this.tagForm.set({ name: tag.name, color: tag.color });
  }

  protected updateTagForm(field: keyof TagForm, value: string): void {
    this.tagForm.update((form) => ({ ...form, [field]: value }));
  }

  protected async saveTag(): Promise<void> {
    const form = this.tagForm();
    if (!form.name.trim()) return;
    this.saving.set(true);
    this.tagError.set(null);
    try {
      const editing = this.editingTag();
      if (editing) {
        await this.api.updateTag(editing.id, {
          version: editing.version, name: form.name.trim(), color: form.color,
        });
      } else {
        await this.api.createTag({ name: form.name.trim(), color: form.color });
      }
      this.editingTag.set(null);
      this.tagForm.set({ name: '', color: '#6D5DFC' });
      await Promise.all([this.loadTags(), this.loadAlerts()]);
    } catch (error) {
      this.tagError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteTag(tag: AlertTag): Promise<void> {
    if (!window.confirm(this.localization.translate('alerts.tags.deleteConfirm'))) return;
    try {
      await this.api.deleteTag(tag.id, tag.version);
      this.selectedTagIds.set(this.selectedTagIds().filter((id) => id !== tag.id));
      await Promise.all([this.loadTags(), this.loadAlerts()]);
    } catch (error) {
      this.tagError.set(error instanceof ApiRequestError && error.code === 'ALERT_TAG_IN_USE'
        ? this.localization.translate('alerts.tags.inUse')
        : this.errorMessage(error));
    }
  }

  protected alertPageTo(page: number): void {
    if (page < 0 || page >= this.alertTotalPages()) return;
    this.alertPage.set(page);
    void this.loadAlerts();
  }

  protected templatePageTo(page: number): void {
    if (page < 0 || page >= this.templateTotalPages() || page === this.templatePage()) return;
    this.templatePage.set(page);
  }

  protected historyPageTo(page: number): void {
    if (page < 0 || page >= this.historyTotalPages()) return;
    this.historyPage.set(page);
    void this.loadHistory();
  }

  protected parameterForm(key: string): ParameterForm {
    return this.form().parameters[key];
  }

  protected dynamic(key: string): string {
    return this.localization.translateDynamic(key);
  }

  protected templateClassName(templateKey: string): string {
    const separator = templateKey.lastIndexOf('.');
    return separator < 0 ? templateKey : templateKey.substring(separator + 1);
  }

  protected statusMessage(execution: AlertExecution): string {
    if (execution.status === 'ERROR') return execution.errorMessage ?? execution.errorType ?? '—';
    if (execution.statusMessage === null) return '—';
    return JSON.stringify(execution.statusMessage);
  }

  private emptyForm(): AlertForm {
    return {
      templateId: null,
      name: '',
      description: '',
      cronExpression: '0 0 * * * *',
      enabled: true,
      allowConcurrentExecutions: false,
      tagIds: [],
      parameters: {},
    };
  }

  private formForTemplate(template: AlertTemplate | null): AlertForm {
    if (!template) return this.emptyForm();
    const parameters: Record<string, ParameterForm> = {};
    for (const parameter of template.parameters) {
      parameters[parameter.key] = this.defaultParameterForm(parameter);
    }
    return { ...this.emptyForm(), templateId: template.id, parameters };
  }

  private defaultParameterForm(parameter: AlertTemplateParameter): ParameterForm {
    return {
      configured: parameter.required || parameter.defaultValue !== null || !parameter.bindingAllowed,
      source: parameter.options.length ? 'OPTION' : 'TEXT',
      textValue: parameter.defaultValue ?? parameter.options[0] ?? '',
      configurationId: null,
      secretId: null,
    };
  }

  private alertFieldErrors(error: unknown): AlertFormErrors {
    const errors: AlertFormErrors = {};
    if (error instanceof ApiRequestError) {
      if (error.fieldErrors['templateId']) errors.template = error.fieldErrors['templateId'];
      if (error.fieldErrors['name']) errors.name = error.fieldErrors['name'];
      if (error.fieldErrors['cronExpression']) errors.cron = error.fieldErrors['cronExpression'];
    }
    const message = this.errorMessage(error);
    if (!errors.cron && message.toLocaleLowerCase().startsWith('invalid cron expression:'))
      errors.cron = message;
    return errors;
  }

  private showFieldErrors(errors: AlertFormErrors): void {
    this.formError.set(null);
    this.formFieldErrors.set(errors);
    const firstField = (['template', 'name', 'cron'] as const).find((field) => errors[field]);
    if (!firstField) return;

    const fieldIds: Record<AlertFormField, string> = {
      template: 'alert-template-search',
      name: 'alert-name',
      cron: 'alert-cron',
    };
    requestAnimationFrame(() => {
      const field = this.elementRef.nativeElement.querySelector<HTMLElement>(`#${fieldIds[firstField]}`);
      field?.focus({ preventScroll: true });
      field?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  private clearFieldError(field: AlertFormField): void {
    if (!this.formFieldErrors()[field]) return;
    this.formFieldErrors.update((errors) => {
      const updated = { ...errors };
      delete updated[field];
      return updated;
    });
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : this.localization.translate('alerts.error');
  }
}
