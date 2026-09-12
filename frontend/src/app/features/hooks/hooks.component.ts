import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ApiRequestError } from '../../core/api/configuration-api.service';
import {
  Hook,
  HookApiService,
  HookInvocation,
  HookMode,
  HookOption,
  HookOptions,
  HookOutcome,
  HookTargetType,
  HookTargetWriteRequest,
} from '../../core/api/hook-api.service';
import { SecretApiService } from '../../core/api/secret-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

type HookTab = 'hooks' | 'history';

interface HookTargetForm {
  type: HookTargetType;
  resourceId: number;
  resourceName: string;
  enabled: boolean;
  continueOn: HookOutcome[];
  busyWaitMinutes: number;
}

interface HookForm {
  name: string;
  description: string;
  enabled: boolean;
  mode: HookMode;
  tokenSecretId: number | null;
  maxConcurrentInvocations: number | null;
  rateLimitCount: number | null;
  rateLimitWindowSeconds: number | null;
  targets: HookTargetForm[];
}

interface TokenSecretForm {
  name: string;
  value: string;
}

const EMPTY_OPTIONS: HookOptions = { targets: [], secrets: [] };
const ALL_OUTCOMES: readonly HookOutcome[] = ['SUCCESS', 'WARN', 'ERROR'];

@Component({
  selector: 'app-hooks',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './hooks.component.html',
  styleUrl: './hooks.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HooksComponent implements OnInit, OnDestroy {
  protected readonly localization = inject(LocalizationService);
  private readonly api = inject(HookApiService);
  private readonly secretApi = inject(SecretApiService);

  protected readonly activeTab = signal<HookTab>('hooks');
  protected readonly hooks = signal<readonly Hook[]>([]);
  protected readonly hookChoices = signal<readonly Hook[]>([]);
  protected readonly options = signal<HookOptions>(EMPTY_OPTIONS);
  protected readonly invocations = signal<readonly HookInvocation[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly pageIndex = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly historyHookId = signal<number | null>(null);
  protected readonly historyPageIndex = signal(0);
  protected readonly historyTotalPages = signal(0);
  protected readonly editorOpen = signal(false);
  protected readonly editing = signal<Hook | null>(null);
  protected readonly form = signal<HookForm>(this.emptyForm());
  protected readonly formError = signal<string | null>(null);
  protected readonly tokenSecretDialogOpen = signal(false);
  protected readonly tokenSecretSaving = signal(false);
  protected readonly tokenSecretForm = signal<TokenSecretForm>(this.emptyTokenSecretForm());
  protected readonly tokenSecretError = signal<string | null>(null);
  protected readonly newTargetType = signal<HookTargetType>('ALERT');
  protected readonly newTargetId = signal<number | null>(null);
  protected readonly outcomes = ALL_OUTCOMES;
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  async ngOnInit(): Promise<void> {
    await this.loadAll();
  }

  ngOnDestroy(): void {
    this.stopRefresh();
  }

  protected dynamic(key: string): string { return this.localization.translateDynamic(key); }

  protected async changeTab(tab: HookTab): Promise<void> {
    this.activeTab.set(tab);
    if (tab === 'history') {
      await this.loadHistory();
      this.startRefresh();
    } else {
      this.stopRefresh();
      await this.loadHooks();
    }
  }

  protected async applySearch(): Promise<void> {
    this.pageIndex.set(0);
    await this.loadHooks();
  }

  protected async goToPage(index: number): Promise<void> {
    if (index < 0 || index >= this.totalPages() || index === this.pageIndex()) return;
    this.pageIndex.set(index);
    await this.loadHooks();
  }

  protected async goToHistoryPage(index: number): Promise<void> {
    if (index < 0 || index >= this.historyTotalPages() || index === this.historyPageIndex()) return;
    this.historyPageIndex.set(index);
    await this.loadHistory();
  }

  protected async selectHistoryHook(id: number | null): Promise<void> {
    this.historyHookId.set(id);
    this.historyPageIndex.set(0);
    await this.loadHistory();
  }

  protected openCreate(): void {
    this.editing.set(null);
    this.form.set(this.emptyForm());
    this.newTargetType.set('ALERT');
    this.newTargetId.set(null);
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected openEdit(hook: Hook): void {
    this.editing.set(hook);
    this.form.set({
      name: hook.name,
      description: hook.description ?? '',
      enabled: hook.enabled,
      mode: hook.mode,
      tokenSecretId: hook.tokenSecretId,
      maxConcurrentInvocations: hook.maxConcurrentInvocations,
      rateLimitCount: hook.rateLimitCount,
      rateLimitWindowSeconds: this.durationSeconds(hook.rateLimitWindow),
      targets: hook.targets.map((target) => ({
        type: target.type,
        resourceId: target.resourceId,
        resourceName: target.resourceName,
        enabled: target.enabled,
        continueOn: [...target.continueOn],
        busyWaitMinutes: Math.max(1, Math.round((this.durationSeconds(target.busyWaitTimeout) ?? 1800) / 60)),
      })),
    });
    this.newTargetType.set('ALERT');
    this.newTargetId.set(null);
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected patchForm<K extends keyof Omit<HookForm, 'targets'>>(key: K, value: HookForm[K]): void {
    this.form.update((form) => ({ ...form, [key]: value }));
  }

  protected openTokenSecretCreate(): void {
    this.tokenSecretForm.set(this.emptyTokenSecretForm());
    this.tokenSecretError.set(null);
    this.tokenSecretDialogOpen.set(true);
  }

  protected closeTokenSecretCreate(): void {
    if (!this.tokenSecretSaving()) {
      this.tokenSecretForm.set(this.emptyTokenSecretForm());
      this.tokenSecretError.set(null);
      this.tokenSecretDialogOpen.set(false);
    }
  }

  protected patchTokenSecretForm<K extends keyof TokenSecretForm>(key: K, value: TokenSecretForm[K]): void {
    this.tokenSecretForm.update((form) => ({ ...form, [key]: value }));
  }

  protected generateTokenSecretValue(): void {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    let binary = '';
    for (const byte of bytes)
      binary += String.fromCharCode(byte);

    this.patchTokenSecretForm('value', btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', ''));
  }

  protected async createTokenSecret(): Promise<void> {
    const form = this.tokenSecretForm();
    if (!form.name.trim() || !form.value) {
      this.tokenSecretError.set(this.dynamic('hooks.tokenSecretCreate.required'));
      return;
    }

    this.tokenSecretSaving.set(true);
    this.tokenSecretError.set(null);
    try {
      const secret = await this.secretApi.createSecret({
        name: form.name.trim(), description: null, valueType: 'STRING', value: form.value, tagIds: [], writable: false,
      });
      this.tokenSecretForm.set(this.emptyTokenSecretForm());
      this.patchForm('tokenSecretId', secret.id);
      try {
        this.options.set(await this.api.options());
      } catch {
        this.options.update((options) => ({
          ...options,
          secrets: [...options.secrets, {
            id: secret.id,
            name: secret.name,
            recoverable: secret.recoveryStatus === 'RECOVERABLE',
          }],
        }));
      }
      this.tokenSecretDialogOpen.set(false);
      this.notice.set(this.dynamic('hooks.tokenSecretCreate.created'));
    } catch (error) {
      this.tokenSecretError.set(this.errorMessage(error));
    } finally {
      this.tokenSecretSaving.set(false);
    }
  }

  protected targetOptions(type = this.newTargetType()): readonly HookOption[] {
    const selected = new Set(this.form().targets.filter((target) => target.type === type).map((target) => target.resourceId));
    return this.options().targets.filter((target) => target.type === type && !selected.has(target.id));
  }

  protected changeNewTargetType(type: HookTargetType): void {
    this.newTargetType.set(type);
    this.newTargetId.set(null);
  }

  protected addTarget(): void {
    const option = this.options().targets.find((candidate) => candidate.type === this.newTargetType() && candidate.id === this.newTargetId());
    if (!option) return;
    this.form.update((form) => ({
      ...form,
      targets: [...form.targets, {
        type: option.type,
        resourceId: option.id,
        resourceName: option.name,
        enabled: option.enabled,
        continueOn: ['SUCCESS'],
        busyWaitMinutes: 30,
      }],
    }));
    this.newTargetId.set(null);
  }

  protected patchTarget<K extends keyof HookTargetForm>(index: number, key: K, value: HookTargetForm[K]): void {
    this.form.update((form) => ({
      ...form,
      targets: form.targets.map((target, candidate) => candidate === index ? { ...target, [key]: value } : target),
    }));
  }

  protected toggleOutcome(index: number, outcome: HookOutcome, checked: boolean): void {
    const target = this.form().targets[index];
    const continueOn = checked
      ? ALL_OUTCOMES.filter((candidate) => candidate === outcome || target.continueOn.includes(candidate))
      : target.continueOn.filter((candidate) => candidate !== outcome);
    this.patchTarget(index, 'continueOn', continueOn);
  }

  protected moveTarget(index: number, offset: -1 | 1): void {
    const destination = index + offset;
    if (destination < 0 || destination >= this.form().targets.length) return;
    this.form.update((form) => {
      const targets = [...form.targets];
      [targets[index], targets[destination]] = [targets[destination], targets[index]];
      return { ...form, targets };
    });
  }

  protected removeTarget(index: number): void {
    this.form.update((form) => ({ ...form, targets: form.targets.filter((_, candidate) => candidate !== index) }));
  }

  protected async save(): Promise<void> {
    const form = this.form();
    if (!form.name.trim() || form.enabled && !form.targets.length) {
      this.formError.set(this.dynamic('hooks.form.required'));
      return;
    }
    if ((form.rateLimitCount === null) !== (form.rateLimitWindowSeconds === null)) {
      this.formError.set(this.dynamic('hooks.form.ratePair'));
      return;
    }
    if (form.maxConcurrentInvocations !== null && form.maxConcurrentInvocations < 1
        || form.rateLimitCount !== null && form.rateLimitCount < 1
        || form.rateLimitWindowSeconds !== null && form.rateLimitWindowSeconds < 1
        || form.targets.some((target) => target.busyWaitMinutes < 1)) {
      this.formError.set(this.dynamic('hooks.form.positive'));
      return;
    }

    const targets: HookTargetWriteRequest[] = form.targets.map((target) => ({
      type: target.type,
      resourceId: target.resourceId,
      continueOn: target.continueOn,
      busyWaitTimeout: `PT${target.busyWaitMinutes}M`,
    }));
    this.saving.set(true);
    this.formError.set(null);
    try {
      const editing = this.editing();
      const request = {
        ...(editing ? { version: editing.version, enabled: form.enabled } : {}),
        name: form.name.trim(),
        description: form.description.trim() || null,
        mode: form.mode,
        tokenSecretId: form.tokenSecretId,
        maxConcurrentInvocations: form.maxConcurrentInvocations,
        rateLimitCount: form.rateLimitCount,
        rateLimitWindow: form.rateLimitWindowSeconds === null ? null : `PT${form.rateLimitWindowSeconds}S`,
        targets,
      };
      if (editing) await this.api.update(editing.id, request);
      else await this.api.create(request);
      this.editorOpen.set(false);
      this.notice.set(this.dynamic(editing ? 'hooks.updated' : 'hooks.created'));
      await this.loadAll(false);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async rotate(hook: Hook): Promise<void> {
    if (!window.confirm(this.dynamic('hooks.rotateConfirm').replace('{name}', hook.name))) return;
    try {
      await this.api.rotate(hook);
      this.notice.set(this.dynamic('hooks.rotated'));
      await this.loadHooks();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected async remove(hook: Hook): Promise<void> {
    try {
      const impact = await this.api.deletionImpact(hook.id);
      const message = this.dynamic('hooks.deleteConfirm').replace('{name}', hook.name)
        .replace('{invocations}', String(impact.invocationCount)).replace('{targets}', String(impact.targetResultCount));
      if (!window.confirm(message)) return;
      await this.api.delete(hook);
      this.notice.set(this.dynamic('hooks.deleted'));
      await this.loadAll(false);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected invocationUrl(hook: Hook): string { return this.api.invocationUrl(hook.publicId); }

  protected async copyUrl(hook: Hook): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.invocationUrl(hook));
      this.notice.set(this.dynamic('hooks.urlCopied'));
    } catch {
      this.error.set(this.dynamic('hooks.copyFailed'));
    }
  }

  private async loadAll(showLoading = true): Promise<void> {
    if (showLoading) this.loading.set(true);
    try {
      const [options, choices] = await Promise.all([this.api.options(), this.api.list('', 0, 500)]);
      this.options.set(options);
      this.hookChoices.set(choices.content);
      await this.loadHooks();
      if (this.historyHookId() === null && this.hookChoices().length) this.historyHookId.set(this.hookChoices()[0].id);
      await this.loadHistory();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadHooks(): Promise<void> {
    const result = await this.api.list(this.search(), this.pageIndex());
    this.hooks.set(result.content);
    this.totalPages.set(result.page.totalPages);
    this.totalElements.set(result.page.totalElements);
    if (this.pageIndex() >= result.page.totalPages && result.page.totalPages > 0) {
      this.pageIndex.set(result.page.totalPages - 1);
      await this.loadHooks();
    }
  }

  protected async loadHistory(): Promise<void> {
    const hookId = this.historyHookId();
    if (hookId === null) {
      this.invocations.set([]);
      this.historyTotalPages.set(0);
      return;
    }
    try {
      const result = await this.api.history(hookId, this.historyPageIndex());
      this.invocations.set(result.content);
      this.historyTotalPages.set(result.page.totalPages);
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  private startRefresh(): void {
    this.stopRefresh();
    this.refreshTimer = setInterval(() => {
      if (this.activeTab() === 'history' && this.invocations().some((invocation) => invocation.status === 'RUNNING'))
        void this.loadHistory();
    }, 3000);
  }

  private stopRefresh(): void {
    if (this.refreshTimer !== null) clearInterval(this.refreshTimer);
    this.refreshTimer = null;
  }

  private emptyForm(): HookForm {
    return {
      name: '', description: '', enabled: false, mode: 'PARALLEL', tokenSecretId: null,
      maxConcurrentInvocations: null, rateLimitCount: null, rateLimitWindowSeconds: null, targets: [],
    };
  }

  private emptyTokenSecretForm(): TokenSecretForm {
    return { name: '', value: '' };
  }

  private durationSeconds(value: string | null): number | null {
    if (value === null) return null;
    const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(value);
    if (!match) return null;
    return Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0);
  }

  private errorMessage(error: unknown): string {
    if (error instanceof ApiRequestError && error.code === 'HOOK_ACTIVE') return this.dynamic('hooks.activeDelete');
    return error instanceof Error ? error.message : String(error);
  }
}
