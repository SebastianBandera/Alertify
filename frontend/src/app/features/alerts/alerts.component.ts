import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  Alert,
  AlertApiService,
  AlertBindingOptions,
  AlertExecution,
  AlertExecutionStatus,
  AlertParameterSource,
  AlertParameterWriteRequest,
  AlertTemplate,
  AlertTemplateParameter,
} from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

type AlertTab = 'alerts' | 'templates' | 'history';
type ParameterFormSource = AlertParameterSource | 'OPTION';

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
  parameters: Readonly<Record<string, ParameterForm>>;
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

  protected readonly activeTab = signal<AlertTab>('alerts');
  protected readonly alerts = signal<readonly Alert[]>([]);
  protected readonly templates = signal<readonly AlertTemplate[]>([]);
  protected readonly executions = signal<readonly AlertExecution[]>([]);
  protected readonly bindings = signal<AlertBindingOptions>(EMPTY_BINDINGS);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly templateFilterId = signal<number | null>(null);
  protected readonly pageSize = signal(readStoredPageSize());
  protected readonly alertPage = signal(0);
  protected readonly alertTotalPages = signal(0);
  protected readonly alertTotalElements = signal(0);
  protected readonly historyPage = signal(0);
  protected readonly historyTotalPages = signal(0);
  protected readonly historyTotalElements = signal(0);
  protected readonly templatePage = signal(0);
  protected readonly templateTotalPages = computed(() =>
    Math.ceil(this.templates().length / this.pageSize()),
  );
  protected readonly pagedTemplates = computed(() => {
    const start = this.templatePage() * this.pageSize();
    return this.templates().slice(start, start + this.pageSize());
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
    if (!query) return this.templates();
    return this.templates().filter((template) =>
      this.dynamic(template.nameKey).toLocaleLowerCase().includes(query)
      || this.dynamic(template.descriptionKey).toLocaleLowerCase().includes(query)
      || template.templateKey.toLocaleLowerCase().includes(query),
    );
  });

  async ngOnInit(): Promise<void> {
    await Promise.all([this.loadAlerts(), this.loadTemplates(), this.loadBindings(), this.loadHistory()]);
  }

  protected async selectTab(tab: AlertTab): Promise<void> {
    this.activeTab.set(tab);
    if (tab === 'alerts') await this.loadAlerts();
    if (tab === 'templates') await this.loadTemplates();
    if (tab === 'history') await this.loadHistory();
  }

  protected async loadAlerts(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.listAlerts(
        this.search(), this.templateFilterId(), this.alertPage(), this.pageSize(),
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

  protected showTemplateAlerts(template: AlertTemplate): void {
    this.activeTab.set('alerts');
    this.search.set('');
    this.templateFilterId.set(template.id);
    this.alertPage.set(0);
    void this.loadAlerts();
  }

  protected applyHistoryFilters(): void {
    this.historyPage.set(0);
    void this.loadHistory();
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
    const template = this.templates().find((item) => item.id === templateId) ?? this.templates()[0] ?? null;
    this.form.set(this.formForTemplate(template));
    this.templateSearch.set(template ? this.dynamic(template.nameKey) : '');
    this.templatePickerOpen.set(false);
    this.formError.set(null);
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
      parameters,
    });
    this.templateSearch.set(template ? this.dynamic(template.nameKey) : alert.templateKey);
    this.templatePickerOpen.set(false);
    this.formError.set(null);
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
    });
    this.templateSearch.set(this.dynamic(template.nameKey));
    this.templatePickerOpen.set(false);
  }

  protected updateTemplateSearch(value: string): void {
    this.templateSearch.set(value);
    this.templatePickerOpen.set(true);
    const selected = this.selectedTemplate();
    if (selected && value !== this.dynamic(selected.nameKey)) {
      this.form.update((form) => ({ ...form, templateId: null, parameters: {} }));
    }
  }

  protected patchForm(patch: Partial<Omit<AlertForm, 'parameters'>>): void {
    this.form.update((form) => ({ ...form, ...patch }));
  }

  protected patchParameter(key: string, patch: Partial<ParameterForm>): void {
    this.form.update((form) => ({
      ...form,
      parameters: { ...form.parameters, [key]: { ...form.parameters[key], ...patch } },
    }));
  }

  protected async save(): Promise<void> {
    const form = this.form();
    const template = this.selectedTemplate();
    const editing = this.editingAlert();
    if (!template || !form.name.trim() || !form.cronExpression.trim()) {
      this.formError.set(this.localization.translate('alerts.form.required'));
      return;
    }

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
    try {
      const request = {
        ...(editing ? { version: editing.version } : { templateId: template.id }),
        name: form.name.trim(),
        description: form.description.trim() || null,
        cronExpression: form.cronExpression.trim(),
        enabled: form.enabled,
        parameters,
      };
      if (editing) await this.api.updateAlert(editing.id, request);
      else await this.api.createAlert(request);
      this.editorOpen.set(false);
      await Promise.all([this.loadAlerts(), this.loadTemplates()]);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
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

  protected statusMessage(execution: AlertExecution): string {
    if (execution.status === 'ERROR') return execution.errorMessage ?? execution.errorType ?? '—';
    if (execution.statusMessage === null) return '—';
    return JSON.stringify(execution.statusMessage);
  }

  private emptyForm(): AlertForm {
    return { templateId: null, name: '', description: '', cronExpression: '0 0 * * * *', enabled: true, parameters: {} };
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

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : this.localization.translate('alerts.error');
  }
}
