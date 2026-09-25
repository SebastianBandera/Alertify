import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { ApiRequestError } from '../../core/api/configuration-api.service';
import {
  Pipe,
  PipeApiService,
  PipeBinding,
  PipeExecution,
  PipeImportResult,
  PipeOption,
  PipeOptions,
  PipeOutcome,
  PipeTag,
  PipeStepType,
  PipeStepWriteRequest,
} from '../../core/api/pipe-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

type PipeTab = 'pipes' | 'history';

interface PipeStepForm {
  key: string;
  type: PipeStepType;
  resourceId: number;
  resourceName: string;
  resourceEnabled: boolean;
  timeoutMinutes: number;
  continueOn: PipeOutcome[];
  bindings: PipeBinding[];
  bindingTarget: string;
  bindingSource: string;
}

interface PipeForm {
  name: string;
  description: string;
  enabled: boolean;
  allowConcurrentExecutions: boolean;
  tagIds: number[];
  steps: PipeStepForm[];
}

interface BindingSourceOption {
  readonly value: string;
  readonly label: string;
}

const EMPTY_OPTIONS: PipeOptions = { resources: [] };
const ALL_OUTCOMES: readonly PipeOutcome[] = ['SUCCESS', 'WARN', 'ERROR'];
const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250, 500, 1000] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.pipes.page-size';

function readStoredPageSize(): number {
  try {
    const storedValue = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return PAGE_SIZE_OPTIONS.some((pageSize) => pageSize === storedValue) ? storedValue : 10;
  } catch {
    return 10;
  }
}

@Component({
  selector: 'app-pipes',
  imports: [DatePipe, FormsModule],
  templateUrl: './pipes.component.html',
  styleUrl: './pipes.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PipesComponent implements OnInit, OnDestroy {
  protected readonly localization = inject(LocalizationService);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly api = inject(PipeApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly activeTab = signal<PipeTab>('pipes');
  protected readonly pipes = signal<readonly Pipe[]>([]);
  protected readonly tags = signal<readonly PipeTag[]>([]);
  protected readonly pipeChoices = signal<readonly Pipe[]>([]);
  protected readonly options = signal<PipeOptions>(EMPTY_OPTIONS);
  protected readonly executions = signal<readonly PipeExecution[]>([]);
  protected readonly selectedExecution = signal<PipeExecution | null>(null);
  protected readonly executionFilter = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly countsLoaded = signal(false);
  protected readonly saving = signal(false);
  protected readonly runningId = signal<number | null>(null);
  protected readonly exporting = signal(false);
  protected readonly importing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly pageSize = signal(readStoredPageSize());
  protected readonly pageIndex = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly historyPipeId = signal<number | null>(null);
  protected readonly historyPageIndex = signal(0);
  protected readonly historyTotalPages = signal(0);
  protected readonly historyTotalElements = signal(0);
  protected readonly editorOpen = signal(false);
  protected readonly editing = signal<Pipe | null>(null);
  protected readonly form = signal<PipeForm>(this.emptyForm());
  protected readonly formError = signal<string | null>(null);
  protected readonly tagDialogOpen = signal(false);
  protected readonly editingTag = signal<PipeTag | null>(null);
  protected readonly tagName = signal('');
  protected readonly tagColor = signal('#6D5DFC');
  protected readonly tagError = signal<string | null>(null);
  protected readonly newStepType = signal<PipeStepType>('PROCEDURE');
  protected readonly newStepResourceId = signal<number | null>(null);
  protected readonly outcomes = ALL_OUTCOMES;
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  async ngOnInit(): Promise<void> {
    const tab = this.route.snapshot.queryParamMap.get('tab');
    const executionId = this.route.snapshot.queryParamMap.get('executionId');
    if (tab === 'history' || executionId) this.activeTab.set('history');
    this.executionFilter.set(executionId);
    try {
      await this.loadAll();
    } finally {
      this.countsLoaded.set(true);
    }
    if (this.activeTab() === 'history') this.startRefresh();
  }

  ngOnDestroy(): void { this.stopRefresh(); }

  protected dynamic(key: string): string { return this.localization.translateDynamic(key); }

  protected async changeTab(tab: PipeTab): Promise<void> {
    this.activeTab.set(tab);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab, executionId: tab === 'history' ? this.executionFilter() : null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    if (tab === 'history') {
      await this.loadHistory();
      this.startRefresh();
    } else {
      this.stopRefresh();
      await this.loadPipes();
    }
  }

  protected async applySearch(): Promise<void> {
    this.pageIndex.set(0);
    await this.loadPipes();
  }

  protected async goToPage(index: number): Promise<void> {
    if (index < 0 || index >= this.totalPages() || index === this.pageIndex()) return;
    this.pageIndex.set(index);
    await this.loadPipes();
  }

  protected async goToHistoryPage(index: number): Promise<void> {
    if (index < 0 || index >= this.historyTotalPages() || index === this.historyPageIndex()) return;
    this.historyPageIndex.set(index);
    await this.loadHistory();
  }

  protected async selectHistoryPipe(id: number | null): Promise<void> {
    this.historyPipeId.set(id);
    this.executionFilter.set(null);
    this.selectedExecution.set(null);
    this.historyPageIndex.set(0);
    await this.updateHistoryQuery();
    await this.loadHistory();
  }

  protected async clearExecutionFilter(): Promise<void> {
    this.executionFilter.set(null);
    this.selectedExecution.set(null);
    this.historyPageIndex.set(0);
    await this.updateHistoryQuery();
    await this.loadHistory();
  }

  protected updatePageSize(value: string | number): void {
    const pageSize = Number(value);
    if (!PAGE_SIZE_OPTIONS.some((option) => option === pageSize)) return;

    this.pageSize.set(pageSize);
    this.pageIndex.set(0);
    this.historyPageIndex.set(0);
    try {
      localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(pageSize));
    } catch {
      // The selection still applies to this page when browser storage is unavailable.
    }

    if (this.activeTab() === 'pipes') void this.loadPipes();
    if (this.activeTab() === 'history') void this.loadHistory();
  }

  protected openCreate(): void {
    this.editing.set(null);
    this.form.set(this.emptyForm());
    this.formError.set(null);
    this.newStepType.set('PROCEDURE');
    this.newStepResourceId.set(null);
    this.editorOpen.set(true);
  }

  protected openEdit(pipe: Pipe): void {
    this.editing.set(pipe);
    this.form.set({
      name: pipe.name,
      description: pipe.description ?? '',
      enabled: pipe.enabled,
      allowConcurrentExecutions: pipe.allowConcurrentExecutions,
      tagIds: pipe.tags.map((tag) => tag.id),
      steps: pipe.steps.map((step) => ({
        key: step.key,
        type: step.type,
        resourceId: step.resourceId,
        resourceName: step.resourceName,
        resourceEnabled: step.resourceEnabled,
        timeoutMinutes: this.durationMinutes(step.timeout) ?? 30,
        continueOn: [...step.continueOn],
        bindings: step.bindings.map((binding) => ({ ...binding })),
        bindingTarget: '',
        bindingSource: '',
      })),
    });
    this.formError.set(null);
    this.newStepType.set('PROCEDURE');
    this.newStepResourceId.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected patchForm<K extends keyof Omit<PipeForm, 'steps'>>(key: K, value: PipeForm[K]): void {
    this.form.update((form) => ({ ...form, [key]: value }));
  }

  protected changeNewStepType(type: PipeStepType): void {
    this.newStepType.set(type);
    this.newStepResourceId.set(null);
  }

  protected resourceOptions(type = this.newStepType()): readonly PipeOption[] {
    return this.options().resources.filter((option) => option.type === type);
  }

  protected addStep(): void {
    const option = this.options().resources.find((candidate) =>
      candidate.type === this.newStepType() && candidate.id === this.newStepResourceId());
    if (!option) return;
    this.form.update((form) => ({
      ...form,
      steps: [...form.steps, {
        key: this.nextStepKey(form.steps, option),
        type: option.type,
        resourceId: option.id,
        resourceName: option.name,
        resourceEnabled: option.enabled,
        timeoutMinutes: 30,
        continueOn: ['SUCCESS'],
        bindings: [],
        bindingTarget: '',
        bindingSource: '',
      }],
    }));
    this.newStepResourceId.set(null);
  }

  protected patchStep<K extends keyof PipeStepForm>(index: number, key: K, value: PipeStepForm[K]): void {
    this.form.update((form) => {
      const previousKey = form.steps[index]?.key;
      return {
        ...form,
        steps: form.steps.map((step, candidate) => ({
          ...(candidate === index ? { ...step, [key]: value } : step),
          bindings: key === 'key' && previousKey
            ? step.bindings.map((binding) => binding.sourceStepKey === previousKey
              ? { ...binding, sourceStepKey: String(value) }
              : binding)
            : step.bindings,
        })),
      };
    });
  }

  protected toggleOutcome(index: number, outcome: PipeOutcome, checked: boolean): void {
    const step = this.form().steps[index];
    const continueOn = checked
      ? ALL_OUTCOMES.filter((candidate) => candidate === outcome || step.continueOn.includes(candidate))
      : step.continueOn.filter((candidate) => candidate !== outcome);
    this.patchStep(index, 'continueOn', continueOn);
  }

  protected moveStep(index: number, offset: -1 | 1): void {
    const destination = index + offset;
    if (destination < 0 || destination >= this.form().steps.length) return;
    this.form.update((form) => {
      const steps = [...form.steps];
      [steps[index], steps[destination]] = [steps[destination], steps[index]];
      const movedKey = steps[destination].key;
      for (let position = 0; position < steps.length; position++)
        steps[position] = { ...steps[position], bindings: steps[position].bindings.filter((binding) =>
          steps.findIndex((candidate) => candidate.key === binding.sourceStepKey) < position) };

      const moved = steps.findIndex((candidate) => candidate.key === movedKey);
      if (moved >= 0) steps[moved] = { ...steps[moved], bindingSource: '', bindingTarget: '' };
      return { ...form, steps };
    });
  }

  protected removeStep(index: number): void {
    const key = this.form().steps[index].key;
    this.form.update((form) => ({
      ...form,
      steps: form.steps.filter((_, candidate) => candidate !== index).map((step) => ({
        ...step,
        bindings: step.bindings.filter((binding) => binding.sourceStepKey !== key),
      })),
    }));
  }

  protected toggleTag(tagId: number, checked: boolean): void {
    this.form.update((form) => ({
      ...form,
      tagIds: checked ? [...form.tagIds, tagId] : form.tagIds.filter((id) => id !== tagId),
    }));
  }

  protected artifactInputs(index: number): readonly string[] {
    const step = this.form().steps[index];
    return this.options().resources.find((option) => option.type === step.type && option.id === step.resourceId)
      ?.artifactInputs ?? [];
  }

  protected bindingSources(index: number): readonly BindingSourceOption[] {
    const sources: BindingSourceOption[] = [];
    for (const step of this.form().steps.slice(0, index)) {
      const outputs = this.options().resources.find((option) =>
        option.type === step.type && option.id === step.resourceId)?.outputs ?? [];
      for (const output of outputs)
        sources.push({ value: this.bindingValue(step.key, output), label: `${step.key} → ${output}` });
    }
    return sources;
  }

  protected addBinding(index: number): void {
    const step = this.form().steps[index];
    if (!step.bindingTarget || !step.bindingSource) return;
    const [sourceStepKey, sourceOutputKey] = step.bindingSource.split('\u0000', 2);
    const binding: PipeBinding = { targetParameterKey: step.bindingTarget, sourceStepKey, sourceOutputKey };
    this.patchStep(index, 'bindings', [
      ...step.bindings.filter((candidate) => candidate.targetParameterKey !== binding.targetParameterKey),
      binding,
    ]);
    this.patchStep(index, 'bindingTarget', '');
    this.patchStep(index, 'bindingSource', '');
  }

  protected removeBinding(stepIndex: number, bindingIndex: number): void {
    const bindings = this.form().steps[stepIndex].bindings.filter((_, index) => index !== bindingIndex);
    this.patchStep(stepIndex, 'bindings', bindings);
  }

  protected async save(): Promise<void> {
    const form = this.form();
    const keys = form.steps.map((step) => step.key.trim());
    if (!form.name.trim() || form.enabled && !form.steps.length) {
      this.formError.set(this.dynamic('pipes.form.required'));
      return;
    }
    if (keys.some((key) => !key) || new Set(keys).size !== keys.length) {
      this.formError.set(this.dynamic('pipes.form.uniqueKeys'));
      return;
    }
    if (form.steps.some((step) => step.timeoutMinutes < 1 || !step.continueOn.length)) {
      this.formError.set(this.dynamic('pipes.form.invalidStep'));
      return;
    }

    const steps: PipeStepWriteRequest[] = form.steps.map((step) => ({
      key: step.key.trim(),
      type: step.type,
      resourceId: step.resourceId,
      timeout: `PT${step.timeoutMinutes}M`,
      continueOn: step.continueOn,
      bindings: step.bindings,
    }));
    this.saving.set(true);
    this.formError.set(null);
    try {
      const editing = this.editing();
      const request = {
        ...(editing ? { version: editing.version } : {}),
        name: form.name.trim(),
        description: form.description.trim() || null,
        enabled: form.enabled,
        allowConcurrentExecutions: form.allowConcurrentExecutions,
        tagIds: form.tagIds,
        steps,
      };
      if (editing) await this.api.update(editing.id, request);
      else await this.api.create(request);
      this.editorOpen.set(false);
      this.notice.set(this.dynamic(editing ? 'pipes.updated' : 'pipes.created'));
      await this.loadAll(false);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async run(pipe: Pipe): Promise<void> {
    if (!pipe.enabled && !window.confirm(this.dynamic('pipes.runDisabledConfirm'))) return;
    this.runningId.set(pipe.id);
    this.error.set(null);
    try {
      const accepted = await this.api.run(pipe.id);
      this.executionFilter.set(accepted.executionId);
      this.historyPipeId.set(pipe.id);
      this.historyPageIndex.set(0);
      this.activeTab.set('history');
      await this.updateHistoryQuery();
      await this.loadHistory();
      this.startRefresh();
      this.notice.set(this.dynamic('pipes.runStarted'));
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.runningId.set(null);
    }
  }

  protected async remove(pipe: Pipe): Promise<void> {
    try {
      const impact = await this.api.deletionImpact(pipe.id);
      const message = this.dynamic('pipes.deleteConfirm')
        .replace('{name}', pipe.name)
        .replace('{executions}', String(impact.executionCount))
        .replace('{hooks}', String(impact.hookReferenceCount))
        .replace('{procedures}', String(impact.procedureReferenceCount));
      if (!window.confirm(message)) return;
      await this.api.delete(pipe);
      this.notice.set(this.dynamic('pipes.deleted'));
      await this.loadAll(false);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected openTagManager(): void {
    this.editingTag.set(null);
    this.tagName.set('');
    this.tagColor.set('#6D5DFC');
    this.tagError.set(null);
    this.tagDialogOpen.set(true);
  }

  protected closeTagManager(): void {
    if (!this.saving()) this.tagDialogOpen.set(false);
  }

  protected editTag(tag: PipeTag): void {
    this.editingTag.set(tag);
    this.tagName.set(tag.name);
    this.tagColor.set(tag.color);
  }

  protected async saveTag(): Promise<void> {
    const name = this.tagName().trim();
    if (!name) return;
    this.saving.set(true);
    this.tagError.set(null);
    try {
      const editing = this.editingTag();
      if (editing) await this.api.updateTag(editing.id, { version: editing.version, name, color: this.tagColor() });
      else await this.api.createTag({ name, color: this.tagColor() });
      this.editingTag.set(null);
      this.tagName.set('');
      await Promise.all([this.loadTags(), this.loadPipes()]);
    } catch (error) {
      this.showTagError(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteTag(tag: PipeTag): Promise<void> {
    if (!window.confirm(this.dynamic('pipes.tags.deleteConfirm'))) return;
    this.tagError.set(null);
    try {
      await this.api.deleteTag(tag);
      await Promise.all([this.loadTags(), this.loadPipes()]);
    } catch (error) {
      this.showTagError(error instanceof ApiRequestError && error.code === 'PIPE_TAG_IN_USE'
        ? this.dynamic('pipes.tags.inUse').replace('{name}', tag.name)
        : this.errorMessage(error));
    }
  }

  private showTagError(message: string): void {
    this.tagError.set(message);
    requestAnimationFrame(() => {
      const error = this.elementRef.nativeElement.querySelector<HTMLElement>('#pipe-tag-error');
      error?.focus({ preventScroll: true });
      error?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }

  protected async exportCsv(): Promise<void> {
    this.exporting.set(true);
    try {
      const blob = await this.api.exportCsv();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'alertify-pipes.csv';
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.exporting.set(false);
    }
  }

  protected openImportPicker(): void {
    this.elementRef.nativeElement.querySelector<HTMLInputElement>('#pipe-import-file')?.click();
  }

  protected async importCsv(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !window.confirm(this.dynamic('pipes.importConfirm'))) return;
    this.importing.set(true);
    try {
      const result = await this.api.importCsv(file);
      this.notice.set(this.importNotice(result));
      await this.loadAll(false);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.importing.set(false);
    }
  }

  private async loadAll(showLoading = true): Promise<void> {
    if (showLoading) this.loading.set(true);
    try {
      const [options, choices, tags] = await Promise.all([this.api.options(), this.api.list('', 0, 500), this.api.listTags()]);
      this.options.set(options);
      this.pipeChoices.set(choices.content);
      this.tags.set(tags);
      await Promise.all([this.loadPipes(), this.loadHistory()]);
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadPipes(): Promise<void> {
    const page = await this.api.list(this.search(), this.pageIndex(), this.pageSize());
    this.pipes.set(page.content);
    this.pageIndex.set(page.page.number);
    this.totalPages.set(page.page.totalPages);
    this.totalElements.set(page.page.totalElements);
  }

  protected async loadHistory(): Promise<void> {
    try {
      const executionId = this.executionFilter();
      if (executionId) {
        const execution = await this.api.execution(executionId);
        this.selectedExecution.set(execution);
        this.executions.set([execution]);
        this.historyPageIndex.set(0);
        this.historyTotalPages.set(1);
        this.historyTotalElements.set(1);
        if (execution.status !== 'RUNNING') this.stopRefresh();
        return;
      }
      const page = await this.api.history(this.historyPipeId(), this.historyPageIndex(), this.pageSize());
      this.selectedExecution.set(null);
      this.executions.set(page.content);
      this.historyPageIndex.set(page.page.number);
      this.historyTotalPages.set(page.page.totalPages);
      this.historyTotalElements.set(page.page.totalElements);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  private async loadTags(): Promise<void> {
    this.tags.set(await this.api.listTags());
  }

  private startRefresh(): void {
    this.stopRefresh();
    this.refreshTimer = setInterval(() => void this.loadHistory(), 2000);
  }

  private stopRefresh(): void {
    if (this.refreshTimer !== null) clearInterval(this.refreshTimer);
    this.refreshTimer = null;
  }

  private async updateHistoryQuery(): Promise<void> {
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: 'history', executionId: this.executionFilter() },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  private nextStepKey(steps: readonly PipeStepForm[], option: PipeOption): string {
    const base = option.name.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'step';
    const used = new Set(steps.map((step) => step.key));
    let candidate = base;
    let suffix = 2;
    while (used.has(candidate)) candidate = `${base}-${suffix++}`;
    return candidate;
  }

  private bindingValue(stepKey: string, outputKey: string): string { return `${stepKey}\u0000${outputKey}`; }

  private durationMinutes(value: string | null): number | null {
    if (!value) return null;
    const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value);
    if (!match) return null;
    return Math.max(1, Math.ceil(Number(match[1] ?? 0) * 60 + Number(match[2] ?? 0) + Number(match[3] ?? 0) / 60));
  }

  private emptyForm(): PipeForm {
    return { name: '', description: '', enabled: false, allowConcurrentExecutions: false, tagIds: [], steps: [] };
  }

  private importNotice(result: PipeImportResult): string {
    return this.dynamic('pipes.importSuccess')
      .replace('{created}', String(result.created))
      .replace('{updated}', String(result.updated))
      .replace('{unchanged}', String(result.unchanged));
  }

  private errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
}
