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

interface ParameterForm {
  configured: boolean;
  source: AlertParameterSource;
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

const PAGE_SIZE = 20;
const EMPTY_BINDINGS: AlertBindingOptions = { configurations: [], secrets: [] };

@Component({
  selector: 'app-alerts',
  imports: [DatePipe, FormsModule],
  templateUrl: './alerts.component.html',
  styleUrl: './alerts.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
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
  protected readonly alertPage = signal(0);
  protected readonly alertTotalPages = signal(0);
  protected readonly alertTotalElements = signal(0);
  protected readonly historyPage = signal(0);
  protected readonly historyTotalPages = signal(0);
  protected readonly historyTotalElements = signal(0);
  protected readonly historyAlertId = signal<number | null>(null);
  protected readonly historyStatus = signal<AlertExecutionStatus | ''>('');
  protected readonly editorOpen = signal(false);
  protected readonly editingAlert = signal<Alert | null>(null);
  protected readonly form = signal<AlertForm>(this.emptyForm());
  protected readonly selectedTemplate = computed(() =>
    this.templates().find((template) => template.id === this.form().templateId) ?? null,
  );

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
      const page = await this.api.listAlerts(this.search(), this.alertPage(), PAGE_SIZE);
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
        this.historyAlertId(), this.historyStatus(), this.historyPage(), PAGE_SIZE,
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

  protected applyHistoryFilters(): void {
    this.historyPage.set(0);
    void this.loadHistory();
  }

  protected openCreate(templateId?: number): void {
    this.editingAlert.set(null);
    const template = this.templates().find((item) => item.id === templateId) ?? this.templates()[0] ?? null;
    this.form.set(this.formForTemplate(template));
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected openEdit(alert: Alert): void {
    const template = this.templates().find((item) => item.id === alert.templateId) ?? null;
    const form = this.formForTemplate(template);
    const parameters = { ...form.parameters };
    for (const value of alert.parameters) {
      parameters[value.parameterKey] = {
        configured: true,
        source: value.source,
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
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected changeTemplate(templateId: string | number): void {
    const id = Number(templateId);
    const template = this.templates().find((item) => item.id === id) ?? null;
    const current = this.form();
    this.form.set({ ...this.formForTemplate(template), name: current.name, description: current.description, cronExpression: current.cronExpression, enabled: current.enabled });
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
      parameters.push({
        parameterKey: definition.key,
        source: value.source,
        textValue: value.source === 'TEXT' ? value.textValue : null,
        configurationId: value.source === 'CONFIGURATION' ? value.configurationId : null,
        secretId: value.source === 'SECRET' ? value.secretId : null,
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
      await this.loadAlerts();
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
      await this.loadAlerts();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected alertPageTo(page: number): void {
    if (page < 0 || page >= this.alertTotalPages()) return;
    this.alertPage.set(page);
    void this.loadAlerts();
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
    return { templateId: null, name: '', description: '', cronExpression: '0 */5 * * * *', enabled: true, parameters: {} };
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
      source: 'TEXT',
      textValue: parameter.defaultValue ?? (!parameter.bindingAllowed ? parameter.options[0] ?? '' : ''),
      configurationId: null,
      secretId: null,
    };
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : this.localization.translate('alerts.error');
  }
}
