import { DatePipe, JsonPipe } from '@angular/common';
import { afterNextRender, afterRenderEffect, ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, Injector, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertMessageService } from '../../../core/alert-messages/alert-message.service';
import { AuthService } from '../../../core/auth/auth.service';
import { PipeApiService } from '../../../core/api/pipe-api.service';
import { ApiRequestError } from '../../../core/api/configuration-api.service';
import { RUNTIME_CONFIG } from '../../../core/config/runtime-config';
import { LocalizationService } from '../../../core/i18n/localization.service';
import { DashboardViewSettings, DEFAULT_VIEW_SETTINGS } from '../dashboard-view-settings';
import { DashboardPipeCard, DashboardPipeStep, pipeState } from './dashboard-pipe';
import { DashboardPipeLiveService } from './dashboard-pipe-live.service';

interface StepDetail { readonly result: unknown; readonly resultRedacted: boolean; readonly errorType: string | null; }
interface Menu { readonly id: number; readonly x: number; readonly y: number; }

const NOTICE_MILLIS = 6_000;

export interface DashboardPipeAlertDetail {
  readonly step: DashboardPipeStep;
  readonly opener: HTMLElement | null;
}

@Component({
  selector: 'app-dashboard-pipes',
  imports: [DatePipe, JsonPipe],
  templateUrl: './dashboard-pipes.component.html',
  styleUrls: ['../dashboard.component.css', './dashboard-pipes.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardPipesComponent {
  readonly settings = input<DashboardViewSettings>(DEFAULT_VIEW_SETTINGS);
  readonly search = input('');
  readonly alertDetail = output<DashboardPipeAlertDetail>();
  protected readonly live = inject(DashboardPipeLiveService);
  protected readonly localization = inject(LocalizationService);
  protected readonly auth = inject(AuthService);
  private readonly api = inject(PipeApiService);
  private readonly messages = inject(AlertMessageService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly base = inject(RUNTIME_CONFIG).apiBaseUrl;
  protected readonly menu = signal<Menu | null>(null);
  protected readonly selected = signal<DashboardPipeCard | null>(null);
  protected readonly expanded = signal<string | null>(null);
  protected readonly stepDetail = signal<StepDetail | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly notice = signal('');
  protected readonly busy = signal<number | null>(null);
  protected readonly ranges = signal<Readonly<Record<number, { first: number; last: number; left: number; max: number }>>>({});
  private opener: HTMLElement | null = null;
  private detailRequest = 0;
  private noticeTimer: number | null = null;
  private readonly requestedPipe = signal<number | null>(null);
  protected readonly menuCard = computed(() => this.live.cards().find(card => card.id === this.menu()?.id) ?? null);
  protected readonly visible = computed(() => {
    const settings = this.settings(), terms = this.normalize(this.search()).split(' ').filter(Boolean), muted = this.live.muted();
    return this.live.cards().filter(card => !settings.hiddenStates.includes(pipeState(card))
      && (!card.tags.length || card.tags.some(tag => !settings.hiddenTagIds.includes(tag.id)))
      && !(settings.hideIgnored && muted[card.id] && muted[card.id].kind !== 'silenced')
      && terms.every(term => this.normalize([card.name, card.description, ...card.tags.map(tag => tag.name), this.status(card), ...card.steps.map(step => [step.result.resourceName, this.stepStatus(card, step), this.message(step)].join(' '))].join(' ')).includes(term)))
      .sort((a,b) => Number(!!muted[a.id]) - Number(!!muted[b.id])
        || ({ error: 0, warn: 1, success: 2, none: 3 }[pipeState(a)] - { error: 0, warn: 1, success: 2, none: 3 }[pipeState(b)])
        || Date.parse(b.stateChangedAt ?? b.execution?.finishedAt ?? '1970-01-01') - Date.parse(a.stateChangedAt ?? a.execution?.finishedAt ?? '1970-01-01') || a.name.localeCompare(b.name));
  });

  readonly visibleCount = computed(() => this.visible().length);

  constructor() {
    afterRenderEffect((cleanup) => {
      this.visible(); this.settings();
      const tracks = [...this.host.nativeElement.querySelectorAll<HTMLElement>('.pipe-track')];
      const observer = new ResizeObserver(() => tracks.forEach(track => {
        const card = this.live.cards().find(value => value.id === Number(track.dataset['pipeId']));
        if (card) this.range(card, track);
      }));
      tracks.forEach(track => observer.observe(track));
      cleanup(() => observer.disconnect());
    });
    this.route.queryParamMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(params => { this.requestedPipe.set(Number(params.get('pipe')) || null); });
    effect(() => {
      const cards = this.live.cards(), selected = this.selected();
      if (selected) {
        const current = cards.find(card => card.id === selected.id);
        if (!current) this.closeDetail();
        else if (current !== selected && current.execution?.executionId === selected.execution?.executionId) this.selected.set(current);
      }
      const requested = cards.find(card => card.id === this.requestedPipe());
      if (requested) {
        this.requestedPipe.set(null);
        this.openDetail(requested);
        void this.router.navigate([], { relativeTo: this.route, queryParams: { pipe: null }, queryParamsHandling: 'merge', replaceUrl: true });
      }
      if (this.menu() && !this.menuCard()) this.closeMenu();
    });
    const closeMenu = () => this.closeMenu();
    window.addEventListener('resize', closeMenu);
    window.addEventListener('scroll', closeMenu, true);
    inject(DestroyRef).onDestroy(() => { window.removeEventListener('resize', closeMenu); window.removeEventListener('scroll', closeMenu, true); this.clearNoticeTimer(); });
  }

  protected t(key: string): string { return this.localization.translateDynamic('dashboard.pipe.' + key); }
  private normalize(text: string): string { return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLocaleLowerCase().trim(); }
  protected status(card: DashboardPipeCard): string { return this.t(card.execution?.status === 'RUNNING' ? 'RUNNING' : card.execution?.outcome ?? 'NONE'); }
  protected state(card: DashboardPipeCard): string { return pipeState(card); }
  protected stepStatus(card: DashboardPipeCard, step: DashboardPipeStep): string {
    return this.t(step.result.status === 'PENDING' && card.execution?.status !== 'RUNNING' ? 'NONE' : step.result.status);
  }
  protected stepClasses(step: DashboardPipeStep): string {
    const status = step.result.status;
    const age = this.settings().flatGreens ? 'flat' : this.successAge(step.result.finishedAt);
    return 'alert-card pipe-step' + (this.settings().minified ? ' alert-card--mini' : '')
      + (status === 'SUCCESS' ? ' alert-card--success alert-card--success-' + age : status === 'WARN' ? ' alert-card--warn' : status === 'ERROR' || status === 'MISSING_PIPE_OUTPUT' || status === 'ARTIFACT_UNAVAILABLE' ? ' alert-card--error' : '')
      + (status.startsWith('SKIPPED') ? ' pipe-step--omitted' : '');
  }
  private successAge(at: string | null): string {
    const age = at ? Math.max(0, Date.now() - Date.parse(at)) / 3_600_000 : 0;
    return age < 1 ? 'very-fresh' : age < 24 ? 'fresh' : age < 72 ? 'recent' : age < 120 ? 'aging' : age < 192 ? 'old' : 'very-old';
  }
  protected message(step: DashboardPipeStep): string {
    return step.alertExecution && step.templateKey ? this.messages.tileMessage(step.templateKey, step.alertExecution) : step.result.errorCode ?? '';
  }
  protected duration(step: DashboardPipeStep): string {
    const result = step.result;
    if (!result.resourceExecutionId || !result.startedAt || !result.finishedAt) return '—';
    return this.millis(Date.parse(result.finishedAt) - Date.parse(result.startedAt));
  }
  protected millis(ms: number | null): string { return ms === null ? '—' : ms < 1000 ? Math.max(0,ms) + ' ms' : (ms / 1000).toFixed(1) + ' s'; }
  protected counts(card: DashboardPipeCard): string {
    const total = card.steps.length, main = card.steps.filter(step => step.result.phase === 'MAIN').length;
    const count = this.t(total === 1 ? 'oneStep' : 'steps').replace('{count}', String(total));
    return main === total ? count : count + ' · ' + this.t(total-main === 1 ? 'phases' : 'phasesMany').replace('{main}', String(main)).replace('{closing}', String(total-main));
  }
  protected openMenu(event: Event, card: DashboardPipeCard): void {
    const button = event.currentTarget as HTMLElement; this.opener = button;
    const rect = button.getBoundingClientRect();
    this.menu.set({ id: card.id, x: Math.max(8, rect.right - 250), y: rect.bottom + 5 });
    afterNextRender(() => {
      const menu = this.host.nativeElement.querySelector<HTMLElement>('.pipe-menu');
      if (menu) { menu.style.top = Math.max(8, Math.min(window.innerHeight-menu.offsetHeight-8, rect.bottom+5))+'px'; menu.querySelector<HTMLElement>('[role=menuitem]')?.focus({ preventScroll: true }); }
    }, { injector: this.injector });
  }
  protected closeMenu(restore = false): void { this.menu.set(null); if (restore) this.opener?.focus({ preventScroll: true }); }
  protected moveMenu(event: Event, offset: number): void {
    event.preventDefault(); const items = [...this.host.nativeElement.querySelectorAll<HTMLElement>('.pipe-menu button:not(:disabled)')];
    items[(items.indexOf(document.activeElement as HTMLElement)+offset+items.length)%items.length]?.focus({ preventScroll: true });
  }
  protected mute(card: DashboardPipeCard, kind: 'ignored' | 'silenced' | 'state' | null): void { this.live.mute(card,kind); this.closeMenu(true); }
  protected canRun(card: DashboardPipeCard): boolean { return this.auth.isAdmin || (this.auth.canRunPipesFromDashboard && card.enabled); }
  protected async run(card: DashboardPipeCard): Promise<void> {
    this.closeMenu(true);
    if (this.busy() !== null) return;
    if (this.auth.isAdmin && !card.enabled && !window.confirm(this.localization.translate('alerts.runDisabledConfirm'))) return;
    this.busy.set(card.id); this.clearNoticeTimer(); this.notice.set('');
    try {
      if (this.auth.isAdmin) await this.api.run(card.id);
      else await this.request('/api/dashboard/pipes/'+card.id+'/run', { method: 'POST' });
      this.showNotice(this.t('started').replace('{name}',card.name));
    } catch (error) { this.showNotice(error instanceof ApiRequestError && error.code === 'DASHBOARD_RUN_RATE_LIMIT' ? this.localization.translate('dashboard.run.rateLimited') : this.t('requestFailed')); }
    finally { this.busy.set(null); }
  }
  private showNotice(message: string): void {
    this.clearNoticeTimer();
    this.notice.set(message);
    this.noticeTimer = window.setTimeout(() => {
      this.noticeTimer = null;
      this.notice.set('');
    }, NOTICE_MILLIS);
  }
  private clearNoticeTimer(): void {
    if (this.noticeTimer !== null) window.clearTimeout(this.noticeTimer);
    this.noticeTimer = null;
  }
  protected navigate(card: DashboardPipeCard, definition: boolean): void {
    this.closeMenu(); void this.router.navigate(['/pipes'], { queryParams: definition ? { pipeId: card.id } : { tab: 'history', pipeId: card.id } });
  }
  protected openStep(card: DashboardPipeCard, step: DashboardPipeStep, event: Event): void {
    if (step.result.type !== 'ALERT' || !step.alertExecution) {
      this.openDetail(card, step, event);
      return;
    }
    const opener = [...this.host.nativeElement.querySelectorAll<HTMLElement>('.pipe-track .pipe-step')]
      .find(element => element.dataset['stepKey'] === step.result.key && element.closest<HTMLElement>('.pipe-track')?.dataset['pipeId'] === String(card.id)) ?? null;
    if (this.selected()) this.closeDetail();
    this.closeMenu();
    this.alertDetail.emit({ step, opener });
  }
  protected openDetail(card: DashboardPipeCard, step?: DashboardPipeStep, event?: Event): void {
    if (event?.currentTarget instanceof HTMLElement) this.opener = event.currentTarget;
    this.closeMenu(); this.selected.set(card); this.expanded.set(null); this.stepDetail.set(null); this.detailRequest++;
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('[role=dialog]')?.focus({ preventScroll: true }), { injector: this.injector });
    if (step) void this.expand(card,step);
  }
  protected closeDetail(): void { this.selected.set(null); this.expanded.set(null); this.detailRequest++; this.opener?.focus({ preventScroll: true }); }
  protected trap(event: KeyboardEvent): void {
    if (event.key === 'Escape') { event.preventDefault(); this.closeDetail(); return; }
    if (event.key !== 'Tab') return;
    const dialog = this.host.nativeElement.querySelector<HTMLElement>('[role=dialog]');
    const items = [...dialog?.querySelectorAll<HTMLElement>('button:not(:disabled),summary,[tabindex="0"]') ?? []].filter(item=>item.getClientRects().length);
    const first = items[0], last = items.at(-1);
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
  protected async expand(card: DashboardPipeCard, step: DashboardPipeStep): Promise<void> {
    if (this.expanded() === step.result.key) { this.expanded.set(null); return; }
    this.expanded.set(step.result.key); this.stepDetail.set(null); this.detailLoading.set(false); const request = ++this.detailRequest;
    if (step.result.type !== 'PROCEDURE' || !step.result.resourceExecutionId || !card.execution) return;
    this.detailLoading.set(true);
    try {
      const detail = await this.request<StepDetail>('/api/dashboard/pipes/'+card.id+'/executions/'+card.execution.executionId+'/steps/'+encodeURIComponent(step.result.key));
      if (request === this.detailRequest) this.stepDetail.set(detail);
    } catch { if (request === this.detailRequest) this.showNotice(this.t('requestFailed')); }
    finally { if (request === this.detailRequest) this.detailLoading.set(false); }
  }
  protected range(card: DashboardPipeCard, track: HTMLElement): void {
    const rect = track.getBoundingClientRect(), steps = [...track.querySelectorAll<HTMLElement>('.pipe-step')];
    const visible = steps.map((node,index)=>({index,rect:node.getBoundingClientRect()})).filter(item=>item.rect.right>rect.left+1&&item.rect.left<rect.right-1);
    this.ranges.update(value=>({...value,[card.id]:{first:(visible[0]?.index??0)+1,last:(visible.at(-1)?.index??0)+1,left:track.scrollLeft,max:Math.max(0,track.scrollWidth-track.clientWidth)}}));
  }
  protected scroll(track: HTMLElement, direction: number): void { track.scrollBy({left:direction*Math.max(240,Math.floor(track.clientWidth/265)*265),behavior:'smooth'}); }
  protected seek(track: HTMLElement, event: Event): void { track.scrollTo({left:Number((event.target as HTMLInputElement).value),behavior:'instant'}); }
  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await this.auth.getAccessToken(), response = await fetch(this.base+path,{...init,headers:{Authorization:'Bearer '+token}});
    if (!response.ok) {
      const error = await response.json().catch(() => ({})) as { code?: string };
      throw new ApiRequestError(this.t('requestFailed'), error.code);
    }
    return response.json();
  }
}
