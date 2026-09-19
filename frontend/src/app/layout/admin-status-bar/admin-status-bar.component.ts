import { DOCUMENT } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { SystemStatusSummary } from '../../core/api/system-status-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';

@Component({
  selector: 'app-admin-status-bar',
  templateUrl: './admin-status-bar.component.html',
  styleUrl: './admin-status-bar.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.admin-status-bar-host--error]': 'connectionUnavailable()',
  },
})
export class AdminStatusBarComponent implements AfterViewInit {
  protected readonly localization = inject(LocalizationService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly eventChannel = inject(AdminEventChannelService);
  private readonly statusBar = viewChild<ElementRef<HTMLElement>>('statusBar');
  private readonly summary = signal<SystemStatusSummary | null>(null);
  protected readonly scrolling = signal(false);
  protected readonly connectionUnavailable = computed(() => this.eventChannel.connectionState() === 'reconnecting');
  private readonly statusMessage = computed(() => {
    const summary = this.summary();
    if (!summary) return null;

    const messages: string[] = [];
    const activeExecutions = summary.activeAlertExecutions + summary.activeProcedureExecutions;
    const waitingExecutions = summary.waitingAlertExecutions + summary.waitingProcedureExecutions;
    if (summary.maintenanceModeEnabled) {
      messages.push(activeExecutions === 0
        ? this.localization.translate('adminStatus.maintenanceReady')
        : this.localization.translate('adminStatus.maintenanceActive').replace('{count}', activeExecutions.toString()));
    }
    if (waitingExecutions > 0) {
      messages.push(this.localization.translate('adminStatus.waitingTasks').replace('{count}', waitingExecutions.toString()));
    }
    for (const worker of summary.saturatedWorkers) {
      messages.push(this.localization.translate('adminStatus.saturatedWorker')
        .replace('{worker}', worker.workerName)
        .replace('{count}', worker.waitingCount.toString()));
    }
    if (summary.cronQuietHoursActive) messages.push(this.localization.translate('adminStatus.cronQuietHours'));
    return messages.length > 0 ? messages.join(' · ') : null;
  });
  protected readonly message = computed(() => this.connectionUnavailable()
    ? this.localization.translate('adminStatus.realtimeDisconnected')
    : this.statusMessage());
  private resizeObserver: ResizeObserver | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.resizeObserver?.disconnect());
    this.eventChannel.on('SYSTEM_STATUS')
      .pipe(takeUntilDestroyed())
      .subscribe((summary) => this.summary.set(summary));
    effect(() => {
      this.message();
      queueMicrotask(() => this.updateScrolling());
    });
  }

  ngAfterViewInit(): void {
    this.resizeObserver = new ResizeObserver(() => this.updateScrolling());
    const statusBar = this.statusBar()?.nativeElement;
    if (statusBar) this.resizeObserver.observe(statusBar);
    this.updateScrolling();
  }

  private updateScrolling(): void {
    const statusBar = this.statusBar()?.nativeElement;
    if (!statusBar || !this.message() || this.connectionUnavailable()
        || this.document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      this.scrolling.set(false);
      return;
    }

    this.resizeObserver?.observe(statusBar);
    const track = statusBar.firstElementChild as HTMLElement | null;
    this.scrolling.set(Boolean(track && track.scrollWidth > statusBar.clientWidth));
  }
}
