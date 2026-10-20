import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AlertApiService } from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { SearchableSelectComponent, SearchableSelectOption } from '../../shared/searchable-select/searchable-select.component';
import { AiChatMessageComponent } from '../ai-chat/ai-chat-message.component';
import { PlaywrightWizardApiService, WizardSettings, WizardState, playwrightBrowserLabel } from './playwright-wizard-api.service';
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
  readonly prepared = output<{ id: string; settings: WizardSettings }>();
  readonly dismissed = output<void>();
  private readonly api = inject(PlaywrightWizardApiService);
  private readonly alertApi = inject(AlertApiService);
  private readonly events = inject(AdminEventChannelService);
  private readonly localization = inject(LocalizationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly remote = viewChild(PlaywrightRemoteViewComponent);
  private readonly clientId = crypto.randomUUID();
  protected readonly state = signal<WizardState | null>(null);
  protected readonly activeBrowser = computed(() => playwrightBrowserLabel(this.state()?.activeEngine));
  protected readonly stateError = computed(() => {
    const current = this.state();
    if (current?.errorCode === 'STALE_SELECTION') return this.t('selectionChanged');
    if (current?.errorCode === 'NO_SELECTION') return this.t('selectElement');
    return current?.error ?? '';
  });
  protected readonly drafts = signal<{ id: string; url: string; updatedAt: string }[]>([]);
  protected readonly error = signal('');
  protected readonly pending = signal(false);
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
  protected readonly changed = computed(() => {
    const saved = this.state()?.document.settings;
    const current = this.settings();
    return !!saved && (current.url !== saved.url || current.steps !== saved.steps || current.engine !== saved.engine
      || current.loadTimeoutSeconds !== saved.loadTimeoutSeconds || current.elementTimeoutSeconds !== saved.elementTimeoutSeconds
      || current.mode !== saved.mode || current.browsers.length !== saved.browsers.length
      || current.browsers.some((browser, index) => browser !== saved.browsers[index]));
  });
  protected readonly busy = computed(() => this.pending() || !!this.state()?.busy);
  protected readonly awaitingApproval = computed(() => !!this.state()?.document.proposal);
  protected readonly canPrepare = computed(() => !this.changed() && !!this.state()?.document.testedRevision);
  private active = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  private popup: Window | null = null;
  private popupTimer: ReturnType<typeof setInterval> | null = null;
  private destroyed = false;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

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
    const unload = (): void => { void this.closeBrowser(); };
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
      if (!this.state()?.open || this.pending()) return;
      const active = this.active;
      this.active = false;
      void this.api.request('HEARTBEAT', this.payload({ active })).then(value => this.update(value)).catch(() => this.error.set(this.t('connectionLost')));
    }, 5000);
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
    this.publishPageState();
    if (differentDraft) {
      this.selectedProcedure.set(null);
      void this.api.request<{ id: number; name: string }[]>('PROCEDURE_CATALOG', { id: next.id }).then(values => this.procedures.set(Array.isArray(values) ? values : [])).catch(() => this.procedures.set([]));
    }
    if (!hadLocalEdits) this.settings.set(structuredClone(next.document.settings));
    if (!next.open) { this.detached.set(false); this.popup?.close(); }
    if (!next.attachmentReady) this.attachment.set(null);
  }

  protected async refreshDrafts(): Promise<void> {
    try { this.drafts.set(await this.api.request('LIST')); }
    catch { this.error.set(this.t('requestFailed')); }
  }

  protected async create(): Promise<void> {
    if (this.state()?.open) await this.closeBrowser();
    await this.perform(async () => { this.state.set(null); this.update(await this.api.request('CREATE')); });
  }

  protected async restore(id: string): Promise<void> {
    if (this.state()?.open) await this.closeBrowser();
    await this.perform(async () => { this.state.set(null); this.update(await this.api.request('GET', { id })); });
  }

  protected patch(patch: Partial<WizardSettings>): void {
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
    await this.perform(async () => {
      const next = await this.api.request('SAVE', this.payload({ settings: this.settings() }));
      this.state.set(next);
      this.settings.set(structuredClone(next.document.settings));
    });
  }

  protected async command(operation: string, payload: Record<string, unknown> = {}): Promise<void> {
    await this.perform(async () => {
      if (this.changed()) {
        const saved = await this.api.request('SAVE', this.payload({ settings: this.settings() }));
        this.state.set(saved);
      }
      this.update(await this.api.request(operation, this.payload(payload)));
    });
  }

  protected async send(): Promise<void> {
    if (!this.message().trim() || this.awaitingApproval()) return;
    const message = this.message();
    await this.command('CHAT', { message, locale: this.localization.locale() });
    if (!this.error()) this.message.set('');
  }

  protected attach(): void {
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
      if (this.changed()) {
        const next = await this.api.request('SAVE', this.payload({ settings: this.settings() }));
        this.state.set(next);
      }
      const result = await this.api.request<{ id: string; settings: WizardSettings }>('PREPARE', this.payload({ bypass }));
      this.prepared.emit(result);
    });
  }

  protected async remove(): Promise<void> {
    if (!confirm(this.t('deleteConfirm'))) return;
    await this.perform(async () => { await this.api.request('DELETE', this.payload()); this.state.set(null); await this.refreshDrafts(); });
  }

  protected async back(): Promise<void> { await this.saveBeforeLeaving(); await this.closeBrowser(); this.dismissed.emit(); }

  private async saveBeforeLeaving(): Promise<void> {
    if (!this.state() || !this.changed() || this.state()!.busy) return;
    try { await this.api.request('SAVE', this.payload({ settings: this.settings() })); } catch { /* The saved revision remains recoverable. */ }
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
    try { await action(); }
    catch (error) { if (!this.destroyed) this.error.set(error instanceof Error ? error.message : this.t('requestFailed')); }
    finally { if (!this.destroyed) this.pending.set(false); }
  }
}
