import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, afterRenderEffect, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AlertApiService } from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { SearchableSelectComponent, SearchableSelectOption } from '../../shared/searchable-select/searchable-select.component';
import { AiChatMessageComponent } from '../ai-chat/ai-chat-message.component';
import { PlaywrightWizardApiService, PreparedWizard, WizardSettings, WizardState, normalizeWizardUrl, playwrightBrowserLabel } from './playwright-wizard-api.service';
import { PlaywrightRemoteViewComponent } from './playwright-remote-view.component';

@Component({
  selector: 'app-playwright-wizard',
  imports: [FormsModule, AiChatMessageComponent, PlaywrightRemoteViewComponent, SearchableSelectComponent],
  templateUrl: './playwright-wizard.component.html',
  styleUrl: './playwright-wizard.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(click)': 'activity()', '(keydown)': 'activity()' },
})
export class PlaywrightWizardComponent implements OnInit {
  readonly prepared = output<PreparedWizard>();
  readonly initialDraftId = input<string | null>(null);
  readonly returned = output<WizardState | null>();
  readonly dismissed = output<void>();
  private readonly api = inject(PlaywrightWizardApiService);
  private readonly alertApi = inject(AlertApiService);
  private readonly events = inject(AdminEventChannelService);
  private readonly localization = inject(LocalizationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly remote = viewChild(PlaywrightRemoteViewComponent);
  private readonly messagesViewport = viewChild<ElementRef<HTMLElement>>('messagesViewport');
  private readonly stepsEditor = viewChild<ElementRef<HTMLTextAreaElement>>('stepsEditor');
  private readonly clientId = crypto.randomUUID();
  protected readonly state = signal<WizardState | null>(null);
  protected readonly activeBrowser = computed(() => playwrightBrowserLabel(this.state()?.activeEngine));
  protected readonly testEngines = ['chromium', 'firefox', 'webkit'] as const;
  protected readonly browserLabel = playwrightBrowserLabel;
  protected readonly validationRows = computed(() => {
    const state = this.state();
    const saved = state?.document.settings;
    const current = this.settings();
    const sameSubject = saved && normalizeWizardUrl(current.url) === normalizeWizardUrl(saved.url) && current.steps === saved.steps
      && current.loadTimeoutSeconds === saved.loadTimeoutSeconds && current.elementTimeoutSeconds === saved.elementTimeoutSeconds;
    return this.testEngines.map(engine => ({
      engine, label: playwrightBrowserLabel(engine), required: this.settings().browsers.includes(engine),
      result: sameSubject ? state?.document.testValidation?.results[engine] : undefined,
      running: state?.busy && state.document.lastAction?.operation === 'TEST' && state.document.lastAction.status === 'RUNNING'
        && !!state.document.lastAction.result?.requestedBrowsers?.includes(engine),
    }));
  });
  protected readonly parameterBindings = computed(() => Object.entries(this.state()?.document.parameterBindings ?? {}).map(([parameter, binding]) => ({ parameter, ...binding })));
  protected linked(parameter: string): boolean { return !!this.state()?.document.parameterBindings?.[parameter]; }
  protected readonly stateError = computed(() => {
    const current = this.state();
    if (current?.errorCode === 'STALE_SELECTION') return this.t('selectionChanged');
    if (current?.errorCode === 'NO_SELECTION') return this.t('selectElement');
    return current?.error ?? '';
  });
  protected readonly drafts = signal<{ id: string; url: string; updatedAt: string }[]>([]);
  protected readonly error = signal('');
  protected readonly pending = signal(false);
  protected readonly saving = signal(false);
  protected urlFocused = false;
  protected readonly message = signal('');
  protected readonly attachment = signal<string | null>(null);
  protected readonly detached = signal(false);
  protected readonly references = signal<string[]>([]);
  protected readonly selectedReference = signal('');
  protected readonly credentialReferences = signal<string[]>([]);
  protected readonly credentialField = signal('/username');
  protected readonly isCredentialReference = computed(() => this.credentialReferences().includes(this.selectedReference()));
  protected readonly procedures = signal<{ id: number; name: string }[]>([]);
  protected readonly selectedProcedure = signal<number | null>(null);
  protected readonly procedurePointer = signal('/code');
  protected readonly procedureAlias = signal('codigo');
  protected readonly procedureOptions = computed<readonly SearchableSelectOption[]>(() => this.procedures().map(value => ({ value: value.id, label: value.name })));
  protected readonly allowedProcedures = computed(() => this.procedures().filter(value => this.state()?.document.allowedProcedureIds?.includes(value.id)));
  protected readonly canInsertProcedure = computed(() => !!this.selectedProcedure() && !!this.state()?.document.allowedProcedureIds?.includes(this.selectedProcedure()!) && /^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(this.procedureAlias()) && (!this.procedurePointer() || this.procedurePointer().startsWith('/')));
  protected readonly referenceOptions = computed<readonly SearchableSelectOption[]>(() => this.references().map((reference, index) => ({ value: index, label: reference, description: this.t(reference.startsWith('configs.') ? 'configurationReference' : 'secretReference') })));
  protected readonly selectedReferenceId = computed(() => { const index = this.references().indexOf(this.selectedReference()); return index < 0 ? null : index; });
  protected readonly settings = signal<WizardSettings>({ url: '', steps: '', engine: 'chromium', browsers: ['chromium'], loadTimeoutSeconds: 10, elementTimeoutSeconds: 5, mode: 'ASK' });
  protected readonly stepsScrollTop = signal(0);
  protected readonly stepLineCount = computed(() => this.settings().steps.split(/\r\n?|\n/).length);
  protected readonly stepLineNumbers = computed(() => Array.from({ length: this.stepLineCount() }, (_, index) => index + 1).join('\n'));
  protected readonly stepLineNumberWidth = computed(() => 'calc(' + (Math.max(3, String(this.stepLineCount()).length) + 2) + 'ch + 16px)');
  protected readonly executionProgress = computed(() => {
    const state = this.state();
    const saved = state?.document.settings;
    const local = this.settings();
    const progress = state?.scriptExecution;
    if (!saved || !progress || progress.status === 'UNVERIFIED' || local.steps !== saved.steps || local.url !== saved.url
      || local.engine !== saved.engine || local.loadTimeoutSeconds !== saved.loadTimeoutSeconds || local.elementTimeoutSeconds !== saved.elementTimeoutSeconds) return null;
    return progress;
  });
  protected readonly executionBrowser = computed(() => playwrightBrowserLabel(this.executionProgress()?.engine));
  protected readonly executionLines = computed(() => {
    const progress = this.executionProgress();
    return progress?.commandLines.map((line, index) => {
      const status = index < progress.completed ? 'completed' : line === progress.activeLine ? 'running' : line === progress.failedLine ? 'failed' : 'pending';
      const symbol = ({ completed: '✓', running: '▶', failed: '×', pending: '○' } as const)[status];
      return { line, status, symbol };
    }) ?? [];
  });
  protected readonly pendingLines = computed(() => this.executionLines().filter(line => line.status === 'pending').map(line => line.line));
  protected readonly pendingLineRanges = computed(() => {
    const ranges: string[] = [];
    for (const line of this.pendingLines()) {
      const last = ranges.at(-1);
      const end = last ? Number(last.split('–').at(-1)) : -1;
      if (line === end + 1) ranges[ranges.length - 1] = last!.split('–')[0] + '–' + line;
      else ranges.push(String(line));
    }
    return ranges.join(', ');
  });
  protected readonly failedLine = computed(() => {
    const current = this.state();
    if (current?.scriptExecution) return this.executionProgress()?.failedLine ?? null;
    const action = current?.document.lastAction;
    const line = action?.result?.failedLine;
    return action?.status === 'FAILED' && this.settings().steps === current?.document.settings.steps && typeof line === 'number' && Number.isInteger(line) && line > 0 && line <= this.stepLineCount() ? line : null;
  });
  protected readonly changed = computed(() => {
    const saved = this.state()?.document.settings;
    return !!saved && !sameSettings(this.settings(), saved);
  });
  protected readonly busy = computed(() => this.pending() || !!this.state()?.busy);
  protected readonly chatMessages = computed(() => this.state()?.document.messages.filter(entry => entry.role !== 'SYSTEM') ?? [], {
    equal: (previous, next) => previous.length === next.length && previous.every((entry, index) => {
      const other = next[index];
      return entry.role === other.role && entry.content === other.content && entry.hasImage === other.hasImage && entry.at === other.at;
    }),
  });
  protected readonly awaitingApproval = computed(() => !!this.state()?.document.proposal);
  protected readonly canPrepare = computed(() => !this.changed() && !!this.state()?.document.testedRevision);
  private active = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  private popup: Window | null = null;
  private popupTimer: ReturnType<typeof setInterval> | null = null;
  private destroyed = false;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private saveTask: Promise<void> | null = null;
  private stateVersion = 0;

  constructor() {
    afterRenderEffect(() => {
      const viewport = this.messagesViewport()?.nativeElement;
      this.chatMessages();
      this.busy();
      if (viewport) viewport.scrollTop = viewport.scrollHeight;
    });
  }

  async ngOnInit(): Promise<void> {
    this.events.on('PLAYWRIGHT_WIZARD_UPDATED').pipe(takeUntilDestroyed(this.destroyRef)).subscribe(value => {
      const next = value as WizardState;
      if (next.id === this.state()?.id) this.update(next);
    });
    const listener = (event: MessageEvent): void => {
      if (event.origin !== location.origin || event.source !== this.popup || event.data?.id !== this.state()?.id) return;
      if (event.data.type === 'WIZARD_SELECT') void this.command('SELECT', event.data.selection);
      if (event.data.type === 'WIZARD_SCROLL') void this.command('SCROLL', { delta: event.data.delta });
      if (event.data.type === 'WIZARD_REATTACH') this.detached.set(false);
      if (event.data.type === 'WIZARD_READY') this.publishPageState();
    };
    window.addEventListener('message', listener);
    const unload = (): void => { void this.saveBeforeLeaving().finally(() => this.closeBrowser()); };
    window.addEventListener('pagehide', unload);
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      if (this.timer) clearInterval(this.timer);
      if (this.popupTimer) clearInterval(this.popupTimer);
      if (this.saveTimer) clearTimeout(this.saveTimer);
      window.removeEventListener('message', listener);
      window.removeEventListener('pagehide', unload);
      this.popup?.close();
      void this.saveBeforeLeaving().finally(() => this.closeBrowser());
    });
    await this.refreshDrafts();
    try {
      const bindings = await this.alertApi.bindingOptions();
      this.references.set([...bindings.configurations.filter(value => value.valueType !== 'BINARY').map(value => 'configs.' + value.name), ...bindings.secrets.filter(value => ['STRING', 'EXPRESSION', 'USERNAME_PASSWORD'].includes(value.valueType ?? '')).map(value => 'secrets.' + value.name)]);
      this.credentialReferences.set(bindings.secrets.filter(value => value.valueType === 'USERNAME_PASSWORD').map(value => 'secrets.' + value.name));
    } catch { /* Existing step references can still be entered by name. */ }
    this.timer = setInterval(() => {
      if (!this.state()?.open || this.pending() || this.saving()) return;
      const id = this.state()!.id;
      const version = this.stateVersion;
      const active = this.active;
      this.active = false;
      void this.api.request('HEARTBEAT', this.payload({ active })).then(value => {
        if (this.state()?.id === id && this.stateVersion === version && !this.pending() && !this.saving()) this.update(value);
      }).catch(() => { if (!this.destroyed && this.state()?.id === id && this.stateVersion === version) this.error.set(this.t('connectionLost')); });
    }, 5000);
    if (this.initialDraftId()) await this.restore(this.initialDraftId()!);
  }

  protected t(key: string): string { return this.localization.translateDynamic('alerts.wizard.' + key); }
  protected activity(): void { this.active = true; }
  private publishPageState(): void {
    const current = this.state();
    if (current) this.popup?.postMessage({ type: 'WIZARD_ENGINE', id: current.id, engine: current.activeEngine, currentUrl: current.currentUrl ?? '' }, location.origin);
  }
  private payload(extra: Record<string, unknown> = {}): Record<string, unknown> { return { id: this.state()?.id, clientId: this.clientId, ...extra }; }

  private update(next: WizardState): void {
    if (this.destroyed) return;
    const hadLocalEdits = this.changed();
    const differentDraft = this.state()?.id !== next.id;
    this.state.set(next);
    this.stateVersion++;
    this.publishPageState();
    if (differentDraft) {
      this.urlFocused = false;
      this.selectedProcedure.set(null);
      void this.api.request<{ id: number; name: string }[]>('PROCEDURE_CATALOG', { id: next.id }).then(values => this.procedures.set(Array.isArray(values) ? values : [])).catch(() => this.procedures.set([]));
    }
    if (differentDraft || (!hadLocalEdits && !this.saving())) this.syncSettings(next.document.settings);
    if (!next.open) { this.detached.set(false); this.popup?.close(); }
    if (!next.attachmentReady) this.attachment.set(null);
  }

  private syncSettings(next: WizardSettings): void {
    const value = structuredClone(next);
    if (this.urlFocused) value.url = this.settings().url;
    if (value.url !== this.settings().url || !sameSettings(value, this.settings())) this.settings.set(value);
  }

  protected async refreshDrafts(): Promise<void> {
    try { this.drafts.set(await this.api.request('LIST')); }
    catch { this.error.set(this.t('requestFailed')); }
  }

  protected async create(): Promise<void> {
    await this.perform(async () => { await this.flushSave(); await this.closeBrowser(); this.state.set(null); this.update(await this.api.request('CREATE')); });
  }

  protected async restore(id: string): Promise<void> {
    await this.perform(async () => { await this.flushSave(); await this.closeBrowser(); this.state.set(null); this.update(await this.api.request('GET', { id })); });
  }

  protected normalizeUrl(): void {
    this.urlFocused = false;
    if (this.linked('url')) return;
    const url = normalizeWizardUrl(this.settings().url);
    if (url !== this.settings().url) this.patch({ url });
  }

  protected goToLine(line: number): void {
    const editor = this.stepsEditor()?.nativeElement;
    if (!editor || this.busy()) return;
    const lines = editor.value.split('\n');
    const index = Math.min(lines.length, Math.max(1, line)) - 1;
    const start = lines.slice(0, index).reduce((offset, value) => offset + value.length + 1, 0);
    editor.focus();
    editor.setSelectionRange(start, start + lines[index].length);
    const style = getComputedStyle(editor);
    const lineHeight = parseFloat(style.lineHeight) || 20;
    const paddingTop = parseFloat(style.paddingTop) || 8;
    editor.scrollTop = Math.max(0, index * lineHeight + paddingTop - (editor.clientHeight - lineHeight) / 2);
    this.stepsScrollTop.set(editor.scrollTop);
  }

  protected patch(patch: Partial<WizardSettings>): void {
    patch = { ...patch };
    for (const key of ['url', 'steps', 'loadTimeoutSeconds', 'elementTimeoutSeconds'] as const) if (this.linked(key)) delete patch[key];
    this.settings.update(value => ({ ...value, ...patch }));
    this.activity();
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => { if (!this.busy() && this.changed()) void this.save(); }, 700);
  }

  protected toggleBrowser(browser: string, checked: boolean): void {
    const browsers = this.settings().browsers.filter(value => value !== browser);
    if (checked) browsers.push(browser);
    this.patch({ browsers });
  }

  protected async save(): Promise<void> {
    if (this.busy()) return;
    this.error.set('');
    try { await this.flushSave(); }
    catch (error) { if (!this.destroyed) this.error.set(error instanceof Error ? error.message : this.t('requestFailed')); }
  }

  private flushSave(): Promise<void> {
    if (this.saveTimer) { clearTimeout(this.saveTimer); this.saveTimer = null; }
    if (this.saveTask) return this.saveTask;
    if (!this.state() || !this.changed()) return Promise.resolve();
    const id = this.state()!.id;
    this.saving.set(true);
    this.saveTask = (async () => {
      let edited: boolean;
      do {
        const snapshot = structuredClone(this.settings());
        const settings = { ...snapshot, url: normalizeWizardUrl(snapshot.url) };
        const next = await this.api.request('SAVE', { id, clientId: this.clientId, settings });
        if (this.state()?.id !== id) return;
        edited = !sameSettings(this.settings(), snapshot);
        if (!this.destroyed) {
          this.state.set(next);
          this.stateVersion++;
          if (!edited) this.syncSettings(next.document.settings);
        }
      } while (edited);
    })().finally(() => { this.saveTask = null; this.saving.set(false); });
    return this.saveTask;
  }

  protected async command(operation: string, payload: Record<string, unknown> = {}): Promise<void> {
    await this.perform(async () => {
      this.normalizeUrl();
      await this.flushSave();
      let next = await this.api.request(operation, this.payload(payload));
      if (next.parameterConfirmation) {
        const details = next.parameterConfirmation.procedures.map(value => value.name + ' (' + value.parameter + ')').join('\n');
        if (!confirm(this.t('confirmParameterProcedures') + '\n' + details)) return;
        next = await this.api.request(operation, this.payload({ ...payload, parameterConfirmationToken: next.parameterConfirmation.token }));
      }
      this.update(next);
    });
  }

  protected async send(): Promise<void> {
    if (!this.message().trim() || this.awaitingApproval()) return;
    const message = this.message();
    await this.command('CHAT', { message, locale: this.localization.locale() });
    if (!this.error()) this.message.set('');
  }

  protected attach(): void {
    if (this.state()?.maskEnabled === false && this.state()?.shareUnmaskedWithAi !== true) return;
    const frame = this.remote()?.frame();
    if (!frame) return;
    void fetch(frame.url).then(response => response.blob()).then(blob => {
      const reader = new FileReader();
      reader.onload = () => this.attachment.set(String(reader.result));
      reader.readAsDataURL(blob);
    });
    void this.command('ATTACH', { frameId: frame.id });
  }

  protected insertReference(): void {
    if (!this.selectedReference()) return;
    const instruction = this.isCredentialReference()
      ? 'FILL SECRET ' + JSON.stringify(this.selectedReference().substring('secrets.'.length)) + ' POINTER ' + JSON.stringify(this.credentialField())
      : 'FILL ' + this.selectedReference();
    this.patch({ steps: this.settings().steps + (this.settings().steps ? '\n' : '') + instruction });
  }

  protected async allowProcedure(id: number | null, allow: boolean): Promise<void> {
    if (id === null) return;
    const ids = this.state()?.document.allowedProcedureIds ?? [];
    await this.command('ALLOW_PROCEDURES', { procedureIds: allow ? [...new Set([...ids, id])] : ids.filter(value => value !== id) });
  }

  protected insertProcedure(): void {
    if (!this.canInsertProcedure()) return;
    const procedure = this.procedures().find(value => value.id === this.selectedProcedure())!;
    this.patch({ steps: this.settings().steps + (this.settings().steps ? '\n' : '') + 'CALL PROCEDURE ' + JSON.stringify(procedure.name) + ' POINTER ' + JSON.stringify(this.procedurePointer()) + ' AS ' + this.procedureAlias() });
  }

  protected selectReference(index: number | null): void { this.selectedReference.set(index === null ? '' : this.references()[index] ?? ''); }

  protected detach(): void {
    const url = new URL('playwright-view', document.baseURI);
    url.searchParams.set('id', this.state()!.id);
    if (this.state()!.activeEngine) url.searchParams.set('engine', this.state()!.activeEngine!);
    const popup = window.open(url, 'alertify-playwright-' + this.state()!.id, 'popup,width=1100,height=800');
    if (!popup) return;
    this.popup = popup;
    this.detached.set(true);
    if (this.popupTimer) clearInterval(this.popupTimer);
    this.popupTimer = setInterval(() => { if (popup.closed) { this.detached.set(false); clearInterval(this.popupTimer!); this.popupTimer = null; } }, 500);
  }

  protected reattach(): void { this.popup?.close(); this.detached.set(false); }

  protected async prepare(bypass = false): Promise<void> {
    if (bypass && !confirm(this.t('bypassConfirm'))) return;
    await this.perform(async () => {
      this.normalizeUrl();
      await this.flushSave();
      const result = await this.api.request<PreparedWizard>('PREPARE', this.payload({ bypass }));
      this.prepared.emit(result);
    });
  }

  protected async remove(): Promise<void> {
    if (!confirm(this.t('deleteConfirm'))) return;
    await this.perform(async () => { await this.api.request('DELETE', this.payload()); this.state.set(null); await this.refreshDrafts(); });
  }

  protected async back(): Promise<void> { await this.perform(async () => { await this.flushSave(); await this.closeBrowser(); this.returned.emit(this.state()); this.dismissed.emit(); }); }

  private async saveBeforeLeaving(): Promise<void> {
    if (!this.state() || this.state()!.busy) return;
    try { await this.flushSave(); } catch { /* The saved revision remains recoverable. */ }
  }

  private async closeBrowser(): Promise<void> {
    if (!this.state()?.open) return;
    try { await this.api.request('CLOSE', this.payload()); }
    catch { /* The independent backend and worker lease closes disconnected sessions. */ }
  }

  private async perform(action: () => Promise<void>): Promise<void> {
    if (this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    try { if (this.saveTask) await this.saveTask; await action(); }
    catch (error) { if (!this.destroyed) this.error.set(error instanceof Error ? error.message : this.t('requestFailed')); }
    finally { if (!this.destroyed) this.pending.set(false); }
  }
}

function sameSettings(current: WizardSettings, saved: WizardSettings): boolean {
  return normalizeWizardUrl(current.url) === normalizeWizardUrl(saved.url) && current.steps === saved.steps && current.engine === saved.engine
    && current.loadTimeoutSeconds === saved.loadTimeoutSeconds && current.elementTimeoutSeconds === saved.elementTimeoutSeconds
    && current.mode === saved.mode && current.browsers.length === saved.browsers.length
    && current.browsers.every((browser, index) => browser === saved.browsers[index]);
}
