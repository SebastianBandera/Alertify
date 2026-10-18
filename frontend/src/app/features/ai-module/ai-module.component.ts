import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';

import {
  AiModuleApiService,
  AiModuleState,
  AiManagedConversationDetail,
  AiManagedConversationSummary,
  AiManagedMemorySummary,
  AiManagedMemoryDetail,
  AiManagedMemoryPage,
  AiSettings,
  BrowserLogin,
  DeviceLogin,
} from '../../core/api/ai-module-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { TranslationKey } from '../../core/i18n/localization.types';

type BrowserKind = 'chrome' | 'firefox';
type BrowserLoginChoice = 'AUTO' | 'EXTENSION' | 'DIRECT';
type AiModuleTab = 'general' | 'conversations' | 'memories' | 'codex';

function aiModuleTab(value: string | null): AiModuleTab {
  return value === 'conversations' || value === 'memories' || value === 'codex' ? value : 'general';
}

@Component({
  selector: 'app-ai-module',
  imports: [FormsModule],
  templateUrl: './ai-module.component.html',
  styleUrl: './ai-module.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModuleComponent implements OnInit {
  private readonly api = inject(AiModuleApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly conversationDetail = viewChild<ElementRef<HTMLElement>>('conversationDetail');
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly localization = inject(LocalizationService);
  protected readonly tab = signal<AiModuleTab>('general');
  protected readonly module = signal<AiModuleState | null>(null);
  protected readonly device = signal<DeviceLogin | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly unavailable = signal(false);
  protected readonly loginChoice = signal<BrowserLoginChoice>('AUTO');
  protected readonly detectedBrowser = this.currentBrowser();
  protected readonly detectedExtensionBrowser = signal<BrowserKind | null>(null);
  protected readonly detectedExtensionInstance = signal('');
  protected readonly detectedExtensionProtocolVersion = signal(0);
  protected readonly message = signal<TranslationKey | ''>('');
  private readonly messageParameter = signal('');
  protected readonly error = signal('');
  protected readonly managedConversations = signal<readonly AiManagedConversationSummary[]>([]);
  protected readonly selectedConversation = signal<AiManagedConversationDetail | null>(null);
  protected readonly conversationsLoading = signal(false);
  protected readonly conversationDetailLoading = signal(false);
  protected readonly managedMemories = signal<AiManagedMemoryPage>({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  protected readonly selectedMemory = signal<AiManagedMemoryDetail | null>(null);
  protected readonly memoriesLoading = signal(false);
  protected readonly memoryDetailLoading = signal(false);
  protected readonly form = signal<AiSettings>({
    enabled: false,
    conversationHistorySearchEnabled: true,
    memoryEnabled: true,
    provider: 'CODEX',
    model: '',
    reasoningEffort: '',
    agentInstructions: '',
    refreshPolicy: '',
    reviewerModel: '',
    reviewerReasoningEffort: 'low',
  });
  private intervalId: number | null = null;

  ngOnInit(): void {
    const requestedTab = this.route.snapshot.queryParamMap.get('tab');
    this.tab.set(aiModuleTab(requestedTab));
    this.loadTab(this.tab());
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((parameters) => {
      const tab = aiModuleTab(parameters.get('tab'));
      if (tab === this.tab()) return;

      this.tab.set(tab);
      this.loadTab(tab);
    });
    if (requestedTab !== this.tab()) {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab: this.tab() },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    }
    const extensionListener = (event: MessageEvent<unknown>) => this.onExtensionMessage(event);
    window.addEventListener('message', extensionListener);
    void this.load();
    this.intervalId = window.setInterval(() => void this.load(false), 3_000);
    this.destroyRef.onDestroy(() => {
      window.removeEventListener('message', extensionListener);
      if (this.intervalId !== null) window.clearInterval(this.intervalId);
    });
  }

  protected selectTab(tab: AiModuleTab): void {
    this.tab.set(tab);
    this.loadTab(tab);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
    });
  }

  private loadTab(tab: AiModuleTab): void {
    if (tab === 'conversations') void this.loadManagedConversations();
    if (tab === 'memories') void this.loadManagedMemories();
  }

  protected async loadManagedMemories(page = this.managedMemories().page): Promise<void> {
    if (this.memoriesLoading()) return;
    this.memoriesLoading.set(true);
    this.error.set('');
    try {
      await this.reloadMemories(page);
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.memoriesLoading.set(false);
    }
  }

  private async reloadMemories(page: number): Promise<void> {
    let result = await this.api.managedMemories(page);
    if (page > 0 && page >= result.totalPages) result = await this.api.managedMemories(Math.max(0, result.totalPages - 1));
    this.managedMemories.set(result);
    this.selectedMemory.set(null);
  }

  protected async selectManagedMemory(id: number): Promise<void> {
    if (this.memoryDetailLoading()) return;
    this.memoryDetailLoading.set(true);
    this.error.set('');
    try {
      this.selectedMemory.set(await this.api.managedMemory(id));
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.memoryDetailLoading.set(false);
    }
  }

  protected async deleteManagedMemory(memory: AiManagedMemorySummary): Promise<void> {
    if (!window.confirm(this.localization.translate('ai.memories.deleteConfirm').replace('{key}', memory.key))) return;

    await this.action(async () => {
      await this.api.deleteManagedMemory(memory.id, memory.version);
      await this.reloadMemories(this.managedMemories().page);
      this.message.set('ai.message.memoryDeleted');
    });
  }

  protected async deleteAllManagedMemories(): Promise<void> {
    if (this.managedMemories().totalElements === 0) return;
    if (!window.confirm(this.localization.translate('ai.memories.deleteAllConfirm'))) return;

    await this.action(async () => {
      await this.api.deleteAllManagedMemories();
      await this.reloadMemories(0);
      this.message.set('ai.message.memoriesDeleted');
    });
  }

  protected async loadManagedConversations(): Promise<void> {
    if (this.conversationsLoading()) return;
    this.conversationsLoading.set(true);
    this.error.set('');
    try {
      this.managedConversations.set(await this.api.managedConversations());
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.conversationsLoading.set(false);
    }
  }

  protected async selectManagedConversation(id: number): Promise<void> {
    if (this.conversationDetailLoading()) return;
    this.conversationDetailLoading.set(true);
    this.error.set('');
    try {
      this.selectedConversation.set(await this.api.managedConversation(id));
      afterNextRender(() => {
        if (this.tab() !== 'conversations' || this.selectedConversation()?.id !== id) return;

        const detail = this.conversationDetail()?.nativeElement;
        detail?.focus({ preventScroll: true });
        detail?.scrollIntoView({ block: 'start', behavior: 'instant' });
      }, { injector: this.injector });
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.conversationDetailLoading.set(false);
    }
  }

  protected async deleteManagedConversation(conversation: AiManagedConversationSummary): Promise<void> {
    const confirmation = this.localization.translate('ai.conversations.deleteConfirm').replace('{title}', conversation.title);
    if (!window.confirm(confirmation)) return;

    await this.action(async () => {
      await this.api.deleteManagedConversation(conversation.id);
      if (this.selectedConversation()?.id === conversation.id) this.selectedConversation.set(null);
      this.managedConversations.set(await this.api.managedConversations());
      this.message.set('ai.message.conversationDeleted');
    });
  }

  protected async deleteAllManagedConversations(): Promise<void> {
    const count = this.managedConversations().length;
    if (count === 0) return;

    const confirmation = this.localization.translate('ai.conversations.deleteAllConfirm').replace('{count}', String(count));
    if (!window.confirm(confirmation)) return;

    await this.action(async () => {
      await this.api.deleteAllManagedConversations();
      this.selectedConversation.set(null);
      this.managedConversations.set([]);
      this.message.set('ai.message.conversationsDeleted');
    });
  }

  protected patch<K extends keyof AiSettings>(key: K, value: AiSettings[K]): void {
    this.form.update((current) => ({ ...current, [key]: value }));
  }

  protected setLoginChoice(value: BrowserLoginChoice): void {
    this.loginChoice.set(value);
  }

  protected async save(): Promise<void> {
    await this.action(async () => {
      const settings = await this.api.update(this.form());
      this.form.set(settings);
      this.message.set('ai.message.saved');
    });
  }

  protected async browserLogin(): Promise<void> {
    const loginWindow = window.open('about:blank', '_blank');
    if (!loginWindow) {
      this.error.set(this.localization.translate('ai.extension.popupBlocked'));
      return;
    }
    loginWindow.opener = null;
    await this.action(async () => {
      const mode = this.resolvedLoginMode();
      let login: BrowserLogin | null = null;
      try {
        login = await this.api.beginBrowser(mode);
        if (mode === 'EXTENSION') await this.prepareExtension(login);
        loginWindow.location.href = login.authorizationUrl;
        this.message.set('ai.message.browserLogin');
      } catch (error) {
        if (login) await this.api.cancelLogin().catch(() => undefined);
        throw error;
      }
    }, () => loginWindow.close());
  }

  protected async deviceLogin(): Promise<void> {
    await this.action(async () => {
      const login = await this.api.beginDevice();
      this.device.set(login);
      window.open(login.verificationUri, '_blank', 'noopener');
      this.message.set('ai.message.deviceLogin');
    });
  }

  protected async copyCode(): Promise<void> {
    const code = this.device()?.userCode;
    if (code) await navigator.clipboard.writeText(code);
  }

  protected async cancelLogin(): Promise<void> {
    await this.action(async () => {
      await this.api.cancelLogin();
      this.device.set(null);
      this.message.set('ai.message.loginCancelled');
      await this.load(false);
    });
  }

  protected async refresh(): Promise<void> {
    await this.action(async () => {
      const result = await this.api.refresh();
      this.messageParameter.set(this.formatDate(result.expiresAt));
      this.message.set('ai.message.tokenRefreshed');
      await this.load(false);
    });
  }

  protected async test(): Promise<void> {
    await this.action(async () => {
      await this.api.test();
      this.message.set('ai.message.connectionVerified');
    });
  }

  protected async logout(): Promise<void> {
    await this.action(async () => {
      const result = await this.api.logout();
      this.message.set(result.successful ? 'ai.message.loggedOut' : 'ai.message.loggedOutRevocationFailed');
      await this.load(false);
    });
  }

  protected async downloadExtension(browser: BrowserKind): Promise<void> {
    await this.action(async () => {
      await this.api.downloadExtension(browser);
      this.message.set(browser === 'firefox' ? 'ai.message.firefoxDownloaded' : 'ai.message.chromeDownloaded');
    });
  }

  protected browserOrder(): readonly BrowserKind[] {
    return this.detectedBrowser === 'firefox' ? ['firefox', 'chrome'] : ['chrome', 'firefox'];
  }

  protected browserName(browser: BrowserKind): string {
    return this.localization.translate(browser === 'firefox' ? 'ai.extension.firefox' : 'ai.extension.chrome');
  }

  protected extensionCompatible(): boolean {
    return this.detectedExtensionBrowser() !== null
      && this.detectedExtensionInstance() === this.module()?.extensionInstanceId
      && this.detectedExtensionProtocolVersion() === this.module()?.extensionProtocolVersion;
  }

  protected extensionStatusKey(): TranslationKey {
    if (!this.detectedExtensionBrowser()) return 'ai.extension.notDetected';
    if (this.detectedExtensionInstance() === this.module()?.extensionInstanceId
        && this.detectedExtensionProtocolVersion() !== this.module()?.extensionProtocolVersion)
      return 'ai.extension.updateRequired';

    return this.extensionCompatible() ? 'ai.extension.detected' : 'ai.extension.wrongInstance';
  }

  protected formatDate(value: string): string {
    return value ? new Intl.DateTimeFormat(this.localization.locale(), { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : '—';
  }

  protected formatOptionalDate(value: string | null): string {
    return value ? this.formatDate(value) : '—';
  }

  protected formatJson(value: unknown): string {
    return JSON.stringify(value, null, 2) ?? '—';
  }

  protected modeLabel(mode: string): string {
    const suffix = mode === 'READ_ONLY' ? 'readOnly' : mode === 'DIRECT' ? 'direct' : 'plan';
    return this.localization.translateDynamic(`ai.chat.mode.${suffix}`);
  }

  protected approvalLabel(approvalPolicy: string): string {
    return this.localization.translateDynamic(approvalPolicy === 'AUTO' ? 'ai.chat.approval.auto' : 'ai.chat.approval.ask');
  }

  protected booleanLabel(value: boolean): string {
    return this.localization.translate(value ? 'ai.conversations.yes' : 'ai.conversations.no');
  }

  protected refreshExpiration(value: string): string {
    return value ? this.formatDate(value) : this.localization.translate('ai.codex.notReported');
  }

  protected lastRefresh(value: string): string {
    return value ? this.formatDate(value) : this.localization.translate('ai.codex.notRefreshed');
  }

  protected messageText(): string {
    const key = this.message();
    if (!key) return '';

    return this.localization.translate(key).replace('{expiresAt}', this.messageParameter());
  }

  private resolvedLoginMode(): 'DIRECT' | 'EXTENSION' {
    const choice = this.loginChoice();
    if (choice === 'EXTENSION' && !this.extensionCompatible())
      throw new Error(this.localization.translate('ai.extension.required'));

    if (choice === 'AUTO') return this.extensionCompatible() ? 'EXTENSION' : 'DIRECT';
    return choice;
  }

  private prepareExtension(login: BrowserLogin): Promise<void> {
    if (!this.extensionCompatible() || login.extensionInstanceId !== this.module()?.extensionInstanceId
        || login.extensionProtocolVersion !== this.module()?.extensionProtocolVersion)
      return Promise.reject(new Error(this.localization.translate('ai.extension.required')));

    const requestId = crypto.randomUUID();
    return new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => finish(false), 5_000);
      const listener = (event: MessageEvent<unknown>) => {
        const data = event.data as Record<string, unknown> | null;
        if (event.source !== window || event.origin !== window.location.origin || data?.['source'] !== 'alertify-codex-extension'
            || data['type'] !== 'PREPARED' || data['requestId'] !== requestId)
          return;

        finish(data['successful'] === true);
      };
      const finish = (successful: boolean) => {
        window.clearTimeout(timeout);
        window.removeEventListener('message', listener);
        if (successful) resolve();
        else reject(new Error(this.localization.translate('ai.extension.prepareFailed')));
      };
      window.addEventListener('message', listener);
      window.postMessage({
        source: 'alertify-ai-page',
        type: 'PREPARE',
        requestId,
        instanceId: login.extensionInstanceId,
        protocolVersion: login.extensionProtocolVersion,
        callbackUri: login.callbackUri,
        callbackTicket: login.callbackTicket,
        ticketExpiresAt: login.expiresAt,
      }, window.location.origin);
    });
  }

  private onExtensionMessage(event: MessageEvent<unknown>): void {
    const data = event.data as Record<string, unknown> | null;
    if (event.source !== window || event.origin !== window.location.origin || data?.['source'] !== 'alertify-codex-extension'
        || data['type'] !== 'READY' || typeof data['instanceId'] !== 'string')
      return;

    const browser = data['browser'];
    if (browser !== 'CHROME' && browser !== 'FIREFOX') return;
    this.detectedExtensionBrowser.set(browser.toLowerCase() as BrowserKind);
    this.detectedExtensionInstance.set(data['instanceId']);
    this.detectedExtensionProtocolVersion.set(typeof data['protocolVersion'] === 'number' ? data['protocolVersion'] : 0);
  }

  private probeExtension(): void {
    const instanceId = this.module()?.extensionInstanceId;
    if (!instanceId) return;
    window.postMessage({ source: 'alertify-ai-page', type: 'PING', instanceId }, window.location.origin);
  }

  private currentBrowser(): BrowserKind {
    return navigator.userAgent.toLowerCase().includes('firefox') ? 'firefox' : 'chrome';
  }

  private async load(showLoading = true): Promise<void> {
    if (showLoading) this.loading.set(true);
    try {
      const state = await this.api.module();
      this.module.set(state);
      this.unavailable.set(!state.workerAvailable);
      if (showLoading && state.workerAvailable) this.form.set(state.settings);
      if (state.pendingLoginMode === 'DEVICE') this.device.set(await this.api.deviceState());
      if (state.sessionStatus === 'ACTIVE') this.device.set(null);
      this.error.set('');
      window.setTimeout(() => this.probeExtension());
    } catch (error) {
      this.unavailable.set(true);
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async action(operation: () => Promise<void>, failed?: () => void): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.messageParameter.set('');
    try {
      await operation();
    } catch (error) {
      failed?.();
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.busy.set(false);
    }
  }
}
