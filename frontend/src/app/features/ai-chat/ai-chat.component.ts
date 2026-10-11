import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, computed, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import {
  AiApprovalPolicy,
  AiChatApiError,
  AiChatApiService,
  AiChatMode,
  AiConversationDetail,
  AiConversationSummary,
  AiToolInvocation,
} from '../../core/api/ai-chat-api.service';
import { AiModuleApiService } from '../../core/api/ai-module-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';

@Component({
  selector: 'app-ai-chat',
  imports: [FormsModule],
  templateUrl: './ai-chat.component.html',
  styleUrl: './ai-chat.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiChatComponent implements OnInit {
  private static readonly PANEL_WIDTH_STORAGE_KEY = 'alertify.aiChat.panelWidth';
  private static readonly DEFAULT_PANEL_WIDTH = 480;
  private static readonly MIN_PANEL_WIDTH = 320;
  private static readonly MAX_VIEWPORT_RATIO = 0.9;
  private static readonly AVAILABILITY_REFRESH_MILLISECONDS = 10_000;
  private static readonly BOTTOM_THRESHOLD = 24;

  readonly popout = input(false);
  private readonly api = inject(AiChatApiService);
  private readonly moduleApi = inject(AiModuleApiService);
  protected readonly auth = inject(AuthService);
  private readonly events = inject(AdminEventChannelService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly messagesViewport = viewChild<ElementRef<HTMLElement>>('messagesViewport');
  protected readonly localization = inject(LocalizationService);
  protected readonly open = signal(false);
  protected readonly detached = signal(false);
  protected readonly conversations = signal<readonly AiConversationSummary[]>([]);
  protected readonly selected = signal<AiConversationDetail | null>(null);
  protected readonly mode = signal<AiChatMode>('READ_ONLY');
  protected readonly approvalPolicy = signal<AiApprovalPolicy>('ASK');
  protected readonly message = signal('');
  protected readonly busy = signal(false);
  private readonly waitingForModel = signal(false);
  protected readonly error = signal('');
  protected readonly panelWidth = signal(this.restorePanelWidth());
  protected readonly chatAvailable = computed(() => this.chatUnavailableReason() === '');
  protected readonly modelWorking = computed(() => {
    if (this.waitingForModel()) return true;

    const turns = this.selected()?.turns ?? [];
    return turns.length > 0 && turns[turns.length - 1].status === 'RUNNING';
  });
  protected readonly usageSummary = computed(() => {
    const conversation = this.selected();
    if (!conversation) return '';

    return `${this.localization.translateDynamic('ai.chat.usage.conversation')} ${this.compactNumber(conversation.usage.tokens.totalTokens)}`;
  });
  protected readonly usageTooltip = computed(() => {
    const conversation = this.selected();
    if (!conversation) return this.localization.translateDynamic('ai.chat.usage.unavailable');

    const tokens = conversation.usage.tokens;
    return [
      `${this.localization.translateDynamic('ai.chat.usage.requests')}: ${this.exactNumber(conversation.usage.requestCount)}`,
      `${this.localization.translateDynamic('ai.chat.usage.input')}: ${this.exactNumber(tokens.inputTokens)}`,
      `${this.localization.translateDynamic('ai.chat.usage.cached')}: ${this.exactNumber(tokens.cachedInputTokens)}`,
      `${this.localization.translateDynamic('ai.chat.usage.cacheWrite')}: ${this.exactNumber(tokens.cacheWriteInputTokens)}`,
      `${this.localization.translateDynamic('ai.chat.usage.output')}: ${this.exactNumber(tokens.outputTokens)}`,
      `${this.localization.translateDynamic('ai.chat.usage.reasoning')}: ${this.exactNumber(tokens.reasoningOutputTokens)}`,
      `${this.localization.translateDynamic('ai.chat.usage.total')}: ${this.exactNumber(tokens.totalTokens)}`,
    ].join('\n');
  });
  protected readonly contextMeter = computed(() => {
    const usage = this.selected()?.turns.at(-1)?.usage;
    const capacity = usage?.modelContextWindow ?? 0;
    const used = usage?.lastRequestTotalTokens ?? 0;
    if (capacity <= 0) return this.emptyMeter(this.localization.translateDynamic('ai.chat.context.unavailable'));

    const remaining = this.clampPercent(100 - used * 100 / capacity);
    const tooltip = `${this.localization.translateDynamic('ai.chat.context.title')}: ${this.percent(remaining)} `
      + `${this.localization.translateDynamic('ai.chat.remaining')}\n`
      + `${this.localization.translateDynamic('ai.chat.context.used')}: ${this.exactNumber(used)} / ${this.exactNumber(capacity)}`;
    return { available: true, remaining, label: this.percent(remaining), tooltip };
  });
  protected readonly weeklyMeter = computed(() => {
    const quota = this.selected()?.turns.at(-1)?.usage.quota;
    const now = Date.now();
    const windows = (quota?.windows ?? []).filter((window) => window.remainingPercent !== null
      && (window.windowMinutes ?? 0) >= 6 * 24 * 60
      && (window.windowMinutes ?? 0) <= 8 * 24 * 60
      && (window.resetsAt === null || new Date(window.resetsAt).getTime() > now))
      .sort((left, right) => (left.windowMinutes ?? Number.MAX_SAFE_INTEGER) - (right.windowMinutes ?? Number.MAX_SAFE_INTEGER));
    if (windows.length === 0) return null;

    const weekly = windows[0];
    const remaining = this.clampPercent(weekly.remainingPercent ?? 0);
    const reset = weekly.resetsAt
      ? ` · ${this.localization.translateDynamic('ai.chat.quota.resets')} ${this.dateTime(weekly.resetsAt)}`
      : '';
    const details = [
      `${this.localization.translateDynamic('ai.chat.weekly.title')}: ${this.percent(remaining)} ${this.localization.translateDynamic('ai.chat.remaining')}${reset}`,
    ];
    if (quota?.planType) details.unshift(`${this.localization.translateDynamic('ai.chat.quota.plan')}: ${quota.planType}`);
    return { available: true, remaining, label: this.percent(remaining), tooltip: details.join('\n') };
  });
  protected readonly moduleState = this.moduleApi.moduleState;
  private readonly availabilityChecked = signal(false);
  private readonly availabilityFailed = signal(false);
  private availabilityLoading = false;
  private availabilityIntervalId: number | null = null;
  private resizeCleanup: (() => void) | null = null;
  private popoutWindow: Window | null = null;
  private followLatest = true;
  private autoScrolling = false;
  private scrollAnimationTimeout: number | null = null;
  private scrollFrame: number | null = null;

  ngOnInit(): void {
    void this.refreshAvailability();
    this.availabilityIntervalId = window.setInterval(
      () => void this.refreshAvailability(),
      AiChatComponent.AVAILABILITY_REFRESH_MILLISECONDS,
    );
    const focusListener = () => void this.refreshAvailability();
    const resizeListener = () => this.panelWidth.set(this.clampPanelWidth(this.panelWidth()));
    window.addEventListener('focus', focusListener);
    window.addEventListener('resize', resizeListener);
    if (this.popout()) {
      this.open.set(true);
      void this.load();
    }
    this.events.on('AI_CHAT_UPDATED').pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
      if (event.status === 'DELETED') {
        if (this.selected()?.id === event.conversationId) {
          this.selected.set(null);
          this.waitingForModel.set(false);
        }
        void this.loadConversations();
        return;
      }
      if (this.selected()?.id === event.conversationId) void this.select(event.conversationId, 'follow');
      void this.loadConversations();
    });
    const listener = (event: MessageEvent<unknown>) => {
      if (event.origin === location.origin && (event.data as { type?: string } | null)?.type === 'ALERTIFY_AI_CHAT_REATTACH') {
        this.detached.set(false);
        this.open.set(true);
      }
    };
    window.addEventListener('message', listener);
    this.destroyRef.onDestroy(() => {
      window.removeEventListener('message', listener);
      window.removeEventListener('focus', focusListener);
      window.removeEventListener('resize', resizeListener);
      if (this.availabilityIntervalId !== null) window.clearInterval(this.availabilityIntervalId);
      if (this.scrollAnimationTimeout !== null) window.clearTimeout(this.scrollAnimationTimeout);
      if (this.scrollFrame !== null) window.cancelAnimationFrame(this.scrollFrame);
      this.stopResize();
    });
  }

  protected async toggle(): Promise<void> {
    if (this.open()) {
      this.open.set(false);
      return;
    }

    await this.refreshAvailability();
    if (!this.chatAvailable()) return;

    this.open.set(true);
    await this.load();
  }

  protected async newConversation(): Promise<void> {
    this.selected.set(null);
    this.mode.set('READ_ONLY');
    this.approvalPolicy.set('ASK');
    this.message.set('');
  }

  protected async select(id: number, scrollMode: 'force' | 'follow' | 'none' = 'force'): Promise<void> {
    try {
      const conversation = await this.api.conversation(id);
      this.selected.set(conversation);
      this.mode.set(conversation.mode);
      this.approvalPolicy.set(conversation.approvalPolicy);
      if (scrollMode === 'force' || (scrollMode === 'follow' && this.followLatest))
        this.scrollToLatest(scrollMode === 'force');

    } catch (error) {
      this.showError(error);
    }
  }

  protected async send(): Promise<void> {
    const text = this.message().trim();
    if (!text || this.busy() || !this.chatAvailable() || !!this.activeTurnId()) return;
    this.busy.set(true);
    this.waitingForModel.set(true);
    this.followLatest = true;
    this.scrollToLatest(true);
    this.error.set('');
    try {
      const response = await this.api.submit(this.selected()?.id ?? null, text, this.mode(), this.approvalPolicy(), this.localization.locale());
      this.message.set('');
      await this.select(response.conversationId);
      await this.loadConversations();
    } catch (error) {
      this.showError(error);
    } finally {
      this.waitingForModel.set(false);
      this.busy.set(false);
    }
  }

  protected async approve(tool: AiToolInvocation): Promise<void> {
    await this.toolAction(() => this.api.approve(tool.id), true);
  }

  protected async reject(tool: AiToolInvocation): Promise<void> {
    await this.toolAction(() => this.api.reject(tool.id));
  }

  protected async cancelTurn(turnId: number): Promise<void> {
    await this.toolAction(() => this.api.cancel(turnId));
  }

  protected detach(): void {
    const popup = window.open(new URL('ai-chat', document.baseURI), 'alertify-ai-chat', 'popup,width=560,height=820');
    if (!popup) return;
    this.popoutWindow = popup;
    this.detached.set(true);
    this.open.set(false);
    const interval = window.setInterval(() => {
      if (!this.popoutWindow || !this.popoutWindow.closed) return;
      window.clearInterval(interval);
      this.detached.set(false);
      this.popoutWindow = null;
    }, 500);
  }

  protected reattach(): void {
    window.opener?.postMessage({ type: 'ALERTIFY_AI_CHAT_REATTACH' }, location.origin);
    window.close();
  }

  protected updateMessage(event: Event): void {
    this.message.set((event.target as HTMLTextAreaElement).value);
  }

  protected messageParts(content: string): readonly Readonly<{ text: string; bold: boolean }>[] {
    const parts: Array<Readonly<{ text: string; bold: boolean }>> = [];
    const boldPattern = /\*\*(.+?)\*\*/gs;
    let start = 0;
    for (const match of content.matchAll(boldPattern)) {
      const index = match.index;
      if (index > start) parts.push({ text: content.slice(start, index), bold: false });

      parts.push({ text: match[1], bold: true });
      start = index + match[0].length;
    }
    if (start < content.length) parts.push({ text: content.slice(start), bold: false });
    return parts.length > 0 ? parts : [{ text: content, bold: false }];
  }

  protected messageAuthor(role: string): string {
    if (role === 'USER') return this.auth.displayName;
    if (role === 'ASSISTANT') return this.localization.translateDynamic('ai.chat.role.assistant');
    if (role === 'SYSTEM') return this.localization.translateDynamic('ai.chat.role.system');

    return role;
  }

  protected async handleComposerKeydown(event: KeyboardEvent): Promise<void> {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;

    event.preventDefault();
    await this.send();
  }

  protected noteManualScrollIntent(): void {
    if (!this.autoScrolling) return;

    this.autoScrolling = false;
    const viewport = this.messagesViewport()?.nativeElement;
    if (viewport) viewport.scrollTo({ top: viewport.scrollTop, behavior: 'auto' });
  }

  protected updateScrollFollowing(): void {
    if (this.autoScrolling) return;

    const viewport = this.messagesViewport()?.nativeElement;
    if (!viewport) return;

    this.followLatest = this.distanceFromBottom(viewport) <= AiChatComponent.BOTTOM_THRESHOLD;
  }

  protected chatUnavailableReason(): string {
    if (!this.availabilityChecked()) return this.localization.translateDynamic('ai.chat.unavailable.checking');
    if (this.availabilityFailed() || !this.moduleState()?.workerAvailable)
      return this.localization.translateDynamic('ai.chat.unavailable.worker');
    if (!this.moduleState()?.enabled) return this.localization.translateDynamic('ai.chat.unavailable.disabled');
    if (this.moduleState()?.sessionStatus !== 'ACTIVE')
      return this.localization.translateDynamic('ai.chat.unavailable.session');

    return '';
  }

  protected startResize(event: PointerEvent): void {
    if (this.popout() || event.button !== 0) return;

    event.preventDefault();
    this.stopResize();
    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;
    const move = (moveEvent: PointerEvent) => {
      this.panelWidth.set(this.clampPanelWidth(window.innerWidth - moveEvent.clientX));
    };
    const stop = () => {
      this.persistPanelWidth();
      this.stopResize();
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop, { once: true });
    window.addEventListener('pointercancel', stop, { once: true });
    this.resizeCleanup = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      this.resizeCleanup = null;
    };
  }

  protected resizeWithKeyboard(event: KeyboardEvent): void {
    const adjustment = event.key === 'ArrowLeft' ? 24 : event.key === 'ArrowRight' ? -24 : 0;
    if (!adjustment) return;

    event.preventDefault();
    this.panelWidth.set(this.clampPanelWidth(this.panelWidth() + adjustment));
    this.persistPanelWidth();
  }

  protected minimumPanelWidth(): number {
    return Math.min(AiChatComponent.MIN_PANEL_WIDTH, this.maximumPanelWidth());
  }

  protected maximumPanelWidth(): number {
    return Math.max(1, Math.floor(window.innerWidth * AiChatComponent.MAX_VIEWPORT_RATIO));
  }

  protected pendingTools(): readonly AiToolInvocation[] {
    return this.selected()?.turns.flatMap((turn) => turn.tools).filter((tool) => tool.status === 'PENDING_APPROVAL') ?? [];
  }

  protected activeTurnId(): number | null {
    const turns = this.selected()?.turns ?? [];
    for (let index = turns.length - 1; index >= 0; index -= 1) {
      const turn = turns[index];
      if (turn.status === 'RUNNING' || turn.status === 'WAITING_APPROVAL') return turn.id;
    }
    return null;
  }

  private async load(): Promise<void> {
    await this.loadConversations();
    const selectedSummary = this.conversations().find((conversation) => conversation.id === this.selected()?.id);
    const currentDayConversation = selectedSummary && this.isCurrentLocalDay(selectedSummary.createdAt)
      ? selectedSummary
      : this.conversations().find((conversation) => this.isCurrentLocalDay(conversation.createdAt));
    if (currentDayConversation) {
      await this.select(currentDayConversation.id);
      return;
    }

    await this.newConversation();
  }

  private async loadConversations(): Promise<void> {
    try {
      this.conversations.set(await this.api.conversations());
    } catch (error) {
      this.showError(error);
    }
  }

  private async toolAction(action: () => Promise<void>, waitsForModel = false): Promise<void> {
    if (!this.chatAvailable()) return;

    this.busy.set(true);
    this.waitingForModel.set(waitsForModel);
    if (waitsForModel) {
      this.followLatest = true;
      this.scrollToLatest(true);
    }
    this.error.set('');
    try {
      await action();
      const id = this.selected()?.id;
      if (id) await this.select(id);
    } catch (error) {
      this.showError(error);
    } finally {
      this.waitingForModel.set(false);
      this.busy.set(false);
    }
  }

  private showError(error: unknown): void {
    if (error instanceof AiChatApiError && error.code === 'AI_CHAT_TURN_ACTIVE') {
      this.error.set(this.localization.translateDynamic('ai.chat.error.turnActive'));
      return;
    }

    this.error.set(error instanceof Error ? error.message : 'AI chat request failed.');
  }

  private async refreshAvailability(): Promise<void> {
    if (this.availabilityLoading) return;

    this.availabilityLoading = true;
    try {
      await this.moduleApi.module();
      this.availabilityFailed.set(false);
    } catch {
      this.availabilityFailed.set(true);
    } finally {
      this.availabilityChecked.set(true);
      this.availabilityLoading = false;
    }
  }

  private restorePanelWidth(): number {
    try {
      const stored = Number(localStorage.getItem(AiChatComponent.PANEL_WIDTH_STORAGE_KEY));
      return this.clampPanelWidth(Number.isFinite(stored) && stored > 0 ? stored : AiChatComponent.DEFAULT_PANEL_WIDTH);
    } catch {
      return this.clampPanelWidth(AiChatComponent.DEFAULT_PANEL_WIDTH);
    }
  }

  private clampPanelWidth(width: number): number {
    return Math.round(Math.min(this.maximumPanelWidth(), Math.max(this.minimumPanelWidth(), width)));
  }

  private persistPanelWidth(): void {
    try {
      localStorage.setItem(AiChatComponent.PANEL_WIDTH_STORAGE_KEY, String(this.panelWidth()));
    } catch {
      // The resized width remains active for this page when browser storage is unavailable.
    }
  }

  private stopResize(): void {
    this.resizeCleanup?.();
  }

  private scrollToLatest(force: boolean): void {
    if (!force && !this.followLatest) return;
    if (force) this.followLatest = true;
    if (this.scrollFrame !== null) window.cancelAnimationFrame(this.scrollFrame);
    this.scrollFrame = window.requestAnimationFrame(() => {
      this.scrollFrame = null;
      const viewport = this.messagesViewport()?.nativeElement;
      if (!viewport) return;

      this.autoScrolling = true;
      const behavior: ScrollBehavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth';
      viewport.scrollTo({ top: viewport.scrollHeight, behavior });
      if (this.scrollAnimationTimeout !== null) window.clearTimeout(this.scrollAnimationTimeout);
      this.scrollAnimationTimeout = window.setTimeout(() => {
        this.autoScrolling = false;
        this.followLatest = this.distanceFromBottom(viewport) <= AiChatComponent.BOTTOM_THRESHOLD;
      }, behavior === 'smooth' ? 500 : 0);
    });
  }

  private distanceFromBottom(viewport: HTMLElement): number {
    return Math.max(0, viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight);
  }

  private isCurrentLocalDay(value: string): boolean {
    const candidate = new Date(value);
    if (Number.isNaN(candidate.getTime())) return false;

    const now = new Date();
    return candidate.getFullYear() === now.getFullYear()
      && candidate.getMonth() === now.getMonth()
      && candidate.getDate() === now.getDate();
  }

  private emptyMeter(tooltip: string): Readonly<{ available: boolean; remaining: number; label: string; tooltip: string }> {
    return { available: false, remaining: 0, label: '—', tooltip };
  }

  private compactNumber(value: number): string {
    return new Intl.NumberFormat(this.localization.locale(), { notation: 'compact', maximumFractionDigits: 1 }).format(value);
  }

  private exactNumber(value: number): string {
    return new Intl.NumberFormat(this.localization.locale()).format(value);
  }

  private percent(value: number): string {
    return new Intl.NumberFormat(this.localization.locale(), { style: 'percent', maximumFractionDigits: 1 }).format(value / 100);
  }

  private dateTime(value: string): string {
    return new Intl.DateTimeFormat(this.localization.locale(), { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value));
  }

  private clampPercent(value: number): number {
    return Math.max(0, Math.min(100, value));
  }
}
