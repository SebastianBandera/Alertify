import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnDestroy, OnInit, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
  PipeStepPhase,
  PipeStepWriteRequest,
} from '../../core/api/pipe-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { EditorDraftService } from '../../shared/editor-drafts/editor-draft.service';
import { EditorViewportService } from '../../shared/editor-viewport/editor-viewport.service';
import { ExpressionEditorComponent, ExpressionEditorNames } from '../../shared/expression-editor/expression-editor.component';

type PipeTab = 'pipes' | 'history';

interface BindingEditorForm {
  kind: 'result' | 'artifact';
  originalTarget: string | null;
  target: string;
  sourceStep: string;
  pointer: string;
  artifactSource: string;
  expression: string;
  invalidField: 'target' | 'sourceStep' | 'pointer' | 'artifactSource' | null;
}

interface PipeStepForm {
  key: string;
  type: PipeStepType;
  phase: PipeStepPhase;
  resourceId: number;
  resourceName: string;
  resourceTemplateKey: string;
  resourceEnabled: boolean;
  timeoutMinutes: number;
  continueOn: PipeOutcome[];
  bindings: PipeBinding[];
  bindingEditor: BindingEditorForm | null;
}

interface PipeForm {
  name: string;
  description: string;
  enabled: boolean;
  allowConcurrentExecutions: boolean;
  finallyTimeoutMinutes: number;
  tagIds: number[];
  steps: PipeStepForm[];
}

interface BindingSourceOption {
  readonly value: string;
  readonly label: string;
}

const EMPTY_OPTIONS: PipeOptions = { resources: [], expressionUtilityFunctions: [] };
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
  imports: [DatePipe, FormsModule, ExpressionEditorComponent],
  templateUrl: './pipes.component.html',
  styleUrl: './pipes.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [EditorViewportService],
})
export class PipesComponent implements OnInit, OnDestroy {
  protected readonly localization = inject(LocalizationService);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly api = inject(PipeApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly events = inject(AdminEventChannelService);
  private readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly editorViewport = inject(EditorViewportService);

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
  protected readonly drafts = inject(EditorDraftService);
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
  protected readonly pipeExpressionScopes = ['pipe', 'utils'] as const;
  protected readonly pipeExpressionNames: ExpressionEditorNames = { pipe: ['VALUE'], utils: [] };
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      if (this.drafts.requestedRestore() !== 'pipes') return;
      if (!this.editorOpen()) this.restoreDraft();
      this.drafts.requestedRestore.set(null);
    });
  }

  async ngOnInit(): Promise<void> {
    this.events.on('AI_RESOURCES_INVALIDATED').pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
      if (event.targets.includes('ALL') || event.targets.includes('PIPES'))
        void this.loadAll(false);
    });
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
    this.editorViewport.capture(`pipe-${pipe.id}`);
    this.editing.set(pipe);
    this.form.set({
      name: pipe.name,
      description: pipe.description ?? '',
      enabled: pipe.enabled,
      allowConcurrentExecutions: pipe.allowConcurrentExecutions,
      finallyTimeoutMinutes: this.durationMinutes(pipe.finallyTimeout) ?? 30,
      tagIds: pipe.tags.map((tag) => tag.id),
      steps: pipe.steps.map((step) => ({
        key: step.key,
        type: step.type,
        phase: step.phase,
        resourceId: step.resourceId,
        resourceName: step.resourceName,
        resourceTemplateKey: step.resourceTemplateKey ?? this.templateKeyFor(step),
        resourceEnabled: step.resourceEnabled,
        timeoutMinutes: this.durationMinutes(step.timeout) ?? 30,
        continueOn: [...step.continueOn],
        bindings: step.bindings.map((binding) => ({ ...binding, valueExpression: binding.valueExpression ?? null })),
        bindingEditor: null,
      })),
    });
    this.formError.set(null);
    this.newStepType.set('PROCEDURE');
    this.newStepResourceId.set(null);
    this.editorOpen.set(true);
  }

  protected minimizeDraft(): void {
    if (this.saving()) return;
    this.drafts.save('pipes', this.form(), this.editing());
    this.editorOpen.set(false);
    this.editorViewport.restore();
  }

  protected restoreDraft(): void {
    const draft = this.drafts.read<PipeForm, Pipe>('pipes');
    if (!draft || this.saving()) return;
    this.editorViewport.capture(draft.editing ? `pipe-${draft.editing.id}` : 'pipe-create');
    this.form.set({
      ...draft.form,
      steps: draft.form.steps.map((step) => ({
        key: step.key,
        type: step.type,
        phase: step.phase,
        resourceId: step.resourceId,
        resourceName: step.resourceName,
        resourceEnabled: step.resourceEnabled,
        timeoutMinutes: step.timeoutMinutes,
        continueOn: [...step.continueOn],
        resourceTemplateKey: step.resourceTemplateKey ?? this.templateKeyFor(step),
        bindings: step.bindings.map((binding) => ({ ...binding, valueExpression: binding.valueExpression ?? null })),
        bindingEditor: this.restoreBindingEditor(step),
      })),
    });
    this.editing.set(draft.editing);
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) {
      this.drafts.remove('pipes');
      this.editorOpen.set(false);
      this.editorViewport.restore();
    }
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

  protected templateKeyFor(resource: { type: PipeStepType; resourceId: number; resourceTemplateKey?: string }): string {
    return resource.resourceTemplateKey || this.options().resources.find(option =>
      option.type === resource.type && option.id === resource.resourceId)?.templateKey || '';
  }

  protected templateName(key: string): string { return key.split('.').at(-1) || this.dynamic('pipes.form.templateUnavailable'); }

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
        resourceTemplateKey: option.templateKey,
        resourceEnabled: option.enabled,
        timeoutMinutes: 30,
        phase: 'MAIN',
        continueOn: ['SUCCESS'],
        bindings: [],
        bindingEditor: null,
      }],
    }));
    this.newStepResourceId.set(null);
  }

  protected patchStep<K extends keyof PipeStepForm>(index: number, key: K, value: PipeStepForm[K]): void {
    this.form.update((form) => {
      const previousKey = form.steps[index]?.key;
      return {
        ...form,
        steps: form.steps.map((step, candidate) => {
          const patchedStep = candidate === index ? { ...step, [key]: value } : step;
          return {
            ...patchedStep,
            bindings: key === 'key' && previousKey
              ? patchedStep.bindings.map((binding) => binding.sourceStepKey === previousKey
                ? { ...binding, sourceStepKey: String(value) }
                : binding)
              : patchedStep.bindings,
            bindingEditor: key === 'key' && previousKey && patchedStep.bindingEditor
              ? { ...patchedStep.bindingEditor,
                sourceStep: patchedStep.bindingEditor.sourceStep === previousKey ? String(value) : patchedStep.bindingEditor.sourceStep,
                artifactSource: patchedStep.bindingEditor.artifactSource.startsWith(previousKey + '\u0000')
                  ? String(value) + patchedStep.bindingEditor.artifactSource.slice(previousKey.length) : patchedStep.bindingEditor.artifactSource }
              : patchedStep.bindingEditor,
          };
        }),
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
      for (let position = 0; position < steps.length; position++)
        steps[position] = { ...steps[position], bindings: steps[position].bindings.filter((binding) =>
          steps.findIndex((candidate) => candidate.key === binding.sourceStepKey) < position) };

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

  protected stringInputs(index: number): readonly string[] {
    const step = this.form().steps[index];
    return this.options().resources.find((option) => option.type === step.type && option.id === step.resourceId)
      ?.stringInputs ?? [];
  }

  protected resultSourceSteps(index: number): readonly PipeStepForm[] {
    return this.form().steps.slice(0, index).filter((step) => step.type === 'PROCEDURE'
      && !(this.form().steps[index].phase === 'MAIN' && step.phase === 'FINALLY'));
  }

  protected bindingSources(index: number): readonly BindingSourceOption[] {
    const sources: BindingSourceOption[] = [];
    for (const step of this.form().steps.slice(0, index)) {
      if (this.form().steps[index].phase === 'MAIN' && step.phase === 'FINALLY') continue;
      const outputs = this.options().resources.find((option) =>
        option.type === step.type && option.id === step.resourceId)?.outputs ?? [];
      for (const output of outputs)
        sources.push({ value: this.bindingValue(step.key, output), label: `${step.key} → ${output}` });
    }
    return sources;
  }

  protected openBindingEditor(index: number, binding?: PipeBinding): void {
    if (this.form().steps[index].bindingEditor) return;
    this.patchStep(index, 'bindingEditor', {
      kind: binding ? binding.sourceOutputKey ? 'artifact' : 'result' : this.stringInputs(index).length ? 'result' : 'artifact',
      originalTarget: binding?.targetParameterKey ?? null,
      target: binding?.targetParameterKey ?? '',
      sourceStep: binding?.sourceStepKey ?? '',
      pointer: binding?.sourceResultPointer ?? '',
      artifactSource: binding?.sourceOutputKey ? this.bindingValue(binding.sourceStepKey, binding.sourceOutputKey) : '',
      expression: binding?.valueExpression ?? '',
      invalidField: null,
    });
    this.focusBindingField(index, this.form().steps[index].bindingEditor?.kind === 'artifact' ? 'artifactSource' : 'sourceStep');
  }

  protected patchBindingEditor<K extends keyof BindingEditorForm>(index: number, key: K, value: BindingEditorForm[K]): void {
    const editor = this.form().steps[index].bindingEditor;
    if (!editor) return;
    this.patchStep(index, 'bindingEditor', { ...editor, [key]: value, invalidField: null });
  }

  protected changeBindingKind(index: number, kind: BindingEditorForm['kind']): void {
    const editor = this.form().steps[index].bindingEditor;
    if (!editor) return;
    this.patchStep(index, 'bindingEditor', { ...editor, kind, target: '', invalidField: null });
  }

  protected cancelBindingEditor(index: number): void {
    this.patchStep(index, 'bindingEditor', null);
    requestAnimationFrame(() => this.elementRef.nativeElement.querySelector<HTMLElement>(`[data-binding-add="${index}"]`)?.focus({ preventScroll: true }));
  }

  protected applyBinding(index: number): boolean {
    const step = this.form().steps[index];
    const editor = step.bindingEditor;
    if (!editor) return true;
    const targets = editor.kind === 'result' ? this.stringInputs(index) : this.artifactInputs(index);
    let invalidField: BindingEditorForm['invalidField'] = !targets.includes(editor.target) ? 'target' : null;
    if (!invalidField && editor.kind === 'result') {
      if (!this.resultSourceSteps(index).some(source => source.key === editor.sourceStep)) invalidField = 'sourceStep';
      else if (!editor.pointer.trim().startsWith('/')) invalidField = 'pointer';
    }
    if (!invalidField && editor.kind === 'artifact' && !this.bindingSources(index).some(source => source.value === editor.artifactSource))
      invalidField = 'artifactSource';

    if (invalidField) {
      this.patchStep(index, 'bindingEditor', { ...editor, invalidField });
      this.focusBindingField(index, invalidField);
      return false;
    }
    const [sourceStepKey, sourceOutputKey] = editor.artifactSource.split('\u0000', 2);
    const binding: PipeBinding = {
      targetParameterKey: editor.target,
      sourceStepKey: editor.kind === 'result' ? editor.sourceStep : sourceStepKey,
      sourceOutputKey: editor.kind === 'artifact' ? sourceOutputKey : null,
      sourceResultPointer: editor.kind === 'result' ? editor.pointer.trim() : null,
      valueExpression: editor.kind === 'result' && editor.expression.trim() ? editor.expression : null,
    };
    this.patchStep(index, 'bindings', [...step.bindings.filter(candidate =>
      candidate.targetParameterKey !== editor.originalTarget && candidate.targetParameterKey !== binding.targetParameterKey), binding]);
    this.cancelBindingEditor(index);
    return true;
  }

  private focusBindingField(index: number, field: string): void {
    requestAnimationFrame(() => {
      const input = this.elementRef.nativeElement.querySelector<HTMLElement>(`[data-binding-step="${index}"] [data-binding-field="${field}"]`);
      input?.focus({ preventScroll: true });
      input?.scrollIntoView({ block: 'nearest' });
    });
  }

  private restoreBindingEditor(step: PipeStepForm): BindingEditorForm | null {
    if (step.bindingEditor) return { ...step.bindingEditor, invalidField: null };
    // Drafts made before the compact editor stored their pending fields separately.
    const old = step as PipeStepForm & { bindingTarget?: string; bindingSource?: string;
      resultBindingTarget?: string; resultBindingSourceStep?: string; resultBindingPointer?: string; resultBindingExpression?: string };
    if (old.resultBindingTarget || old.resultBindingSourceStep || old.resultBindingPointer || old.resultBindingExpression)
      return { kind: 'result', originalTarget: null, target: old.resultBindingTarget ?? '', sourceStep: old.resultBindingSourceStep ?? '',
        pointer: old.resultBindingPointer ?? '', expression: old.resultBindingExpression ?? '', artifactSource: '', invalidField: null };
    if (old.bindingTarget || old.bindingSource)
      return { kind: 'artifact', originalTarget: null, target: old.bindingTarget ?? '', sourceStep: '', pointer: '', expression: '',
        artifactSource: old.bindingSource ?? '', invalidField: null };
    return null;
  }

  protected removeBinding(stepIndex: number, bindingIndex: number): void {
    const bindings = this.form().steps[stepIndex].bindings.filter((_, index) => index !== bindingIndex);
    this.patchStep(stepIndex, 'bindings', bindings);
  }

  protected async save(): Promise<void> {
    for (let index = 0; index < this.form().steps.length; index++)
      if (!this.applyBinding(index)) return;
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
    if (!Number.isFinite(form.finallyTimeoutMinutes) || form.finallyTimeoutMinutes < 1 || form.steps.some((step) => !Number.isFinite(step.timeoutMinutes) || step.timeoutMinutes < 1 || !step.continueOn.length)) {
      this.formError.set(this.dynamic('pipes.form.invalidStep'));
      return;
    }

    const steps: PipeStepWriteRequest[] = form.steps.map((step) => ({
      key: step.key.trim(),
      type: step.type,
      phase: step.phase,
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
        finallyTimeout: `PT${form.finallyTimeoutMinutes}M`,
        tagIds: form.tagIds,
        steps,
      };
      if (editing) await this.api.update(editing.id, request);
      else await this.api.create(request);
      this.drafts.remove('pipes');
      this.editorOpen.set(false);
      this.notice.set(this.dynamic(editing ? 'pipes.updated' : 'pipes.created'));
      await this.loadAll(false);
      this.editorViewport.restore();
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
    return { name: '', description: '', enabled: false, allowConcurrentExecutions: false, finallyTimeoutMinutes: 30, tagIds: [], steps: [] };
  }

  private importNotice(result: PipeImportResult): string {
    return this.dynamic('pipes.importSuccess')
      .replace('{created}', String(result.created))
      .replace('{updated}', String(result.updated))
      .replace('{unchanged}', String(result.unchanged));
  }

  private errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
}
