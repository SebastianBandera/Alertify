import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AlertParameterSource } from '../../core/api/alert-api.service';
import { ApiRequestError } from '../../core/api/configuration-api.service';
import {
  Procedure,
  ProcedureApiService,
  ProcedureBindingOptions,
  ProcedureExecution,
  ProcedureExecutionStatus,
  ProcedureParameterWriteRequest,
  ProcedureTag,
  ProcedureTemplate,
  ProcedureTemplateParameter,
} from '../../core/api/procedure-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

type ProcedureTab = 'procedures' | 'templates' | 'history';
type ParameterSource = AlertParameterSource | 'OPTION';

interface ParameterForm {
  configured: boolean;
  source: ParameterSource;
  textValue: string;
  configurationId: number | null;
  secretId: number | null;
  procedureId: number | null;
}

interface ProcedureForm {
  templateId: number | null;
  name: string;
  description: string;
  enabled: boolean;
  tagIds: number[];
  parameters: Readonly<Record<string, ParameterForm>>;
}

const EMPTY_BINDINGS: ProcedureBindingOptions = { configurations: [], secrets: [], procedures: [] };

@Component({
  selector: 'app-procedures',
  imports: [DatePipe, FormsModule],
  templateUrl: './procedures.component.html',
  styleUrl: './procedures.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProceduresComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  private readonly api = inject(ProcedureApiService);
  private readonly elementRef: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly activeTab = signal<ProcedureTab>('procedures');
  protected readonly procedures = signal<readonly Procedure[]>([]);
  protected readonly templates = signal<readonly ProcedureTemplate[]>([]);
  protected readonly tags = signal<readonly ProcedureTag[]>([]);
  protected readonly executions = signal<readonly ProcedureExecution[]>([]);
  protected readonly bindings = signal<ProcedureBindingOptions>(EMPTY_BINDINGS);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly runningId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly templateFilterId = signal<number | null>(null);
  protected readonly historyProcedureId = signal<number | null>(null);
  protected readonly historyStatus = signal<ProcedureExecutionStatus | ''>('');
  protected readonly historyExecutionId = signal<string | null>(null);
  protected readonly editorOpen = signal(false);
  protected readonly editing = signal<Procedure | null>(null);
  protected readonly form = signal<ProcedureForm>(this.emptyForm());
  protected readonly selectedTemplate = computed(() =>
    this.templates().find((template) => template.id === this.form().templateId) ?? null,
  );
  protected readonly formError = signal<string | null>(null);
  protected readonly tagDialogOpen = signal(false);
  protected readonly editingTag = signal<ProcedureTag | null>(null);
  protected readonly tagName = signal('');
  protected readonly tagColor = signal('#7C3AED');
  protected readonly tagError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const executionId = this.route.snapshot.queryParamMap.get('executionId');
    this.historyExecutionId.set(executionId);
    if (executionId) this.activeTab.set('history');
    await this.loadAll();
  }

  protected dynamic(key: string): string {
    return this.localization.translateDynamic(key);
  }

  protected async changeTab(tab: ProcedureTab): Promise<void> {
    this.activeTab.set(tab);
    if (tab === 'procedures') await this.loadProcedures();
    if (tab === 'history') await this.loadHistory();
  }

  protected async applySearch(): Promise<void> {
    await this.loadProcedures();
  }

  protected updateTemplateFilter(templateId: number | null): void {
    this.templateFilterId.set(templateId);
    void this.loadProcedures();
  }

  protected showTemplateProcedures(template: ProcedureTemplate): void {
    this.search.set('');
    this.templateFilterId.set(template.id);
    void this.changeTab('procedures');
  }

  protected openCreate(template: ProcedureTemplate | null = null): void {
    this.editing.set(null);
    this.form.set(this.formForTemplate(template));
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected openEdit(procedure: Procedure): void {
    const template = this.templates().find((candidate) => candidate.id === procedure.templateId) ?? null;
    const form = this.formForTemplate(template);
    const parameters = { ...form.parameters };
    for (const value of procedure.parameters) {
      const definition = template?.parameters.find((candidate) => candidate.key === value.parameterKey);
      parameters[value.parameterKey] = {
        configured: true,
        source: value.source === 'TEXT' && definition?.options.includes(value.textValue ?? '')
          ? 'OPTION' : value.source,
        textValue: value.textValue ?? '',
        configurationId: value.configurationId,
        secretId: value.secretId,
        procedureId: value.procedureId,
      };
    }
    this.editing.set(procedure);
    this.form.set({
      ...form,
      name: procedure.name,
      description: procedure.description ?? '',
      enabled: procedure.enabled,
      tagIds: procedure.tags.map((tag) => tag.id),
      parameters,
    });
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected selectTemplateId(templateId: number | null): void {
    const current = this.form();
    const template = this.templates().find((candidate) => candidate.id === templateId) ?? null;
    this.form.set({
      ...this.formForTemplate(template),
      name: current.name,
      description: current.description,
      enabled: current.enabled,
      tagIds: current.tagIds,
    });
  }

  protected patchForm(patch: Partial<Omit<ProcedureForm, 'parameters'>>): void {
    this.form.update((form) => ({ ...form, ...patch }));
  }

  protected patchParameter(key: string, patch: Partial<ParameterForm>): void {
    this.form.update((form) => ({
      ...form,
      parameters: { ...form.parameters, [key]: { ...form.parameters[key], ...patch } },
    }));
  }

  protected parameterForm(key: string): ParameterForm {
    return this.form().parameters[key];
  }

  protected toggleTag(tagId: number, checked: boolean): void {
    this.form.update((form) => ({
      ...form,
      tagIds: checked ? [...form.tagIds, tagId] : form.tagIds.filter((id) => id !== tagId),
    }));
  }

  protected async save(): Promise<void> {
    const form = this.form();
    const template = this.templates().find((candidate) => candidate.id === form.templateId);
    if (!template || !form.name.trim()) {
      this.formError.set(this.localization.translate('procedures.form.required'));
      return;
    }
    const parameters: ProcedureParameterWriteRequest[] = [];
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
        procedureId: source === 'PROCEDURE' ? value.procedureId : null,
      });
    }
    this.saving.set(true);
    this.formError.set(null);
    try {
      const editing = this.editing();
      const request = {
        ...(editing ? { version: editing.version } : { templateId: template.id }),
        name: form.name.trim(),
        description: form.description.trim() || null,
        enabled: form.enabled,
        tagIds: form.tagIds,
        parameters,
      };
      if (editing) await this.api.updateProcedure(editing.id, request);
      else await this.api.createProcedure(request);
      this.editorOpen.set(false);
      await this.loadAll(false);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async run(procedure: Procedure): Promise<void> {
    if (this.runningId() !== null) return;
    if (!procedure.enabled && !window.confirm(this.localization.translate('procedures.runDisabledConfirm'))) return;
    this.runningId.set(procedure.id);
    try {
      await this.api.runProcedure(procedure.id);
      this.notice.set(this.localization.translate('procedures.runStarted'));
      await this.loadHistory();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.runningId.set(null);
    }
  }

  protected async remove(procedure: Procedure): Promise<void> {
    if (!window.confirm(this.localization.translate('procedures.deleteConfirm').replace('{name}', procedure.name))) return;
    try {
      await this.api.deleteProcedure(procedure.id, procedure.version);
      await this.loadAll(false);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected executionResult(execution: ProcedureExecution): string {
    if (execution.status === 'ERROR') return execution.errorMessage ?? execution.errorType ?? '—';
    if (execution.status === 'RUNNING') return this.localization.translate('procedures.history.running');
    if (execution.resultRedacted) return this.localization.translate('procedures.history.redacted');
    return execution.result === null ? '{}' : JSON.stringify(execution.result);
  }

  protected openTagManager(): void {
    this.editingTag.set(null);
    this.tagName.set('');
    this.tagColor.set('#7C3AED');
    this.tagError.set(null);
    this.tagDialogOpen.set(true);
  }

  protected closeTagManager(): void {
    if (!this.saving()) this.tagDialogOpen.set(false);
  }

  protected editTag(tag: ProcedureTag): void {
    this.editingTag.set(tag);
    this.tagName.set(tag.name);
    this.tagColor.set(tag.color);
  }

  protected async saveTag(): Promise<void> {
    if (!this.tagName().trim()) return;
    this.saving.set(true);
    this.tagError.set(null);
    try {
      const tag = this.editingTag();
      if (tag) await this.api.updateTag(tag, this.tagName().trim(), this.tagColor());
      else await this.api.createTag(this.tagName().trim(), this.tagColor());
      this.tags.set(await this.api.listTags());
      this.editingTag.set(null);
      this.tagName.set('');
    } catch (error) {
      this.tagError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteTag(tag: ProcedureTag): Promise<void> {
    if (!window.confirm(this.localization.translate('procedures.tags.deleteConfirm'))) return;
    try {
      await this.api.deleteTag(tag);
      this.tags.set(await this.api.listTags());
      this.tagError.set(null);
    } catch (error) {
      this.tagError.set(error instanceof ApiRequestError && error.code === 'PROCEDURE_TAG_IN_USE'
        ? this.localization.translate('procedures.tags.inUse')
        : this.errorMessage(error));
    }
  }

  protected async exportCsv(): Promise<void> {
    try {
      const blob = await this.api.exportProcedures();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'alertify-procedures.csv';
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected selectImport(): void {
    this.elementRef.nativeElement.querySelector<HTMLInputElement>('#procedure-import')?.click();
  }

  protected async importCsv(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !window.confirm(this.localization.translate('procedures.importConfirm'))) return;
    try {
      const result = await this.api.importProcedures(file);
      this.notice.set(this.localization.translate('procedures.importSuccess')
        .replace('{created}', String(result.created)).replace('{updated}', String(result.updated))
        .replace('{unchanged}', String(result.unchanged)));
      await this.loadAll(false);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  private async loadAll(showLoading = true): Promise<void> {
    if (showLoading) this.loading.set(true);
    try {
      const [templates, tags, bindings] = await Promise.all([
        this.api.listTemplates(), this.api.listTags(), this.api.bindingOptions(),
      ]);
      this.templates.set(templates);
      this.tags.set(tags);
      this.bindings.set(bindings);
      await Promise.all([this.loadProcedures(), this.loadHistory()]);
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadProcedures(): Promise<void> {
    const page = await this.api.listProcedures(this.search(), this.templateFilterId(), [], 'OR', 0, 500);
    this.procedures.set(page.content);
  }

  protected async loadHistory(): Promise<void> {
    const page = await this.api.listExecutions(this.historyProcedureId(), this.historyStatus(), 0, 200, this.historyExecutionId());
    this.executions.set(page.content);
  }

  protected async clearHistoryExecutionFilter(): Promise<void> {
    this.historyExecutionId.set(null);
    await this.router.navigate([], { relativeTo: this.route, queryParams: { executionId: null }, queryParamsHandling: 'merge', replaceUrl: true });
    await this.loadHistory();
  }

  private emptyForm(): ProcedureForm {
    return { templateId: null, name: '', description: '', enabled: true, tagIds: [], parameters: {} };
  }

  private formForTemplate(template: ProcedureTemplate | null): ProcedureForm {
    if (!template) return this.emptyForm();
    const parameters: Record<string, ParameterForm> = {};
    for (const parameter of template.parameters) parameters[parameter.key] = this.defaultParameter(parameter);
    return { ...this.emptyForm(), templateId: template.id, parameters };
  }

  private defaultParameter(parameter: ProcedureTemplateParameter): ParameterForm {
    const source: ParameterSource = parameter.options.length
      ? 'OPTION'
      : parameter.allowedSources[0] ?? 'TEXT';
    return {
      configured: parameter.required || parameter.defaultValue !== null,
      source,
      textValue: parameter.defaultValue ?? parameter.options[0] ?? '',
      configurationId: null,
      secretId: null,
      procedureId: null,
    };
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
  }
}
