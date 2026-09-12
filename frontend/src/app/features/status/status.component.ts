import { DatePipe, DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import type { EChartsCoreOption } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';

import {
  WorkerActivityHistory,
  WorkerActivitySeries,
  WorkerNodeStatus,
  WorkerStatusApiService,
  WorkerTaskStatus,
} from '../../core/api/worker-status-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

const REFRESH_INTERVAL_MILLIS = 1_000;
const HISTORY_REFRESH_INTERVAL_MILLIS = 60_000;
const HISTORY_MINUTES = 24 * 60;
const HISTORY_PLOT_WIDTH_PIXELS = 1_440;
const HISTORY_COLORS = ['#2563eb', '#dc2626', '#059669', '#9333ea', '#ea580c', '#0891b2', '#4f46e5', '#be123c'];

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer]);

function workerOrder(worker: WorkerNodeStatus): number {
  if (worker.capabilities.includes('STANDARD')) return 0;
  if (worker.capabilities.includes('PLAYWRIGHT')) return 1;
  return 2;
}

@Component({
  selector: 'app-status',
  imports: [DatePipe, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './status.component.html',
  styleUrl: './status.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  private readonly api = inject(WorkerStatusApiService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly selectedTab = signal<'live' | 'history'>('live');
  protected readonly workers = signal<readonly WorkerNodeStatus[]>([]);
  protected readonly loading = signal(true);
  protected readonly refreshing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly history = signal<WorkerActivityHistory | null>(null);
  protected readonly historyLoading = signal(false);
  protected readonly historyRefreshing = signal(false);
  protected readonly historyError = signal<string | null>(null);
  protected readonly historyLastUpdatedAt = signal<Date | null>(null);
  protected readonly historyOptions = computed(() => this.createHistoryOptions(this.history(), this.localization.locale()));
  protected readonly activeWorkerCount = computed(
    () => this.workers().filter((worker) => worker.available).length,
  );
  protected readonly totalExecuted = computed(
    () => this.workers().reduce((total, worker) => total + worker.totalExecuted, 0),
  );
  protected readonly runningCount = computed(
    () => this.workers().reduce((total, worker) => total + worker.runningCount, 0),
  );
  protected readonly waitingCount = computed(
    () => this.workers().reduce((total, worker) => total + worker.waitingCount, 0),
  );
  protected readonly totalExecutedProcedures = computed(
    () => this.workers().reduce((total, worker) => total + worker.totalExecutedProcedures, 0),
  );
  protected readonly runningProcedureCount = computed(
    () => this.workers().reduce((total, worker) => total + worker.runningProcedureCount, 0),
  );
  private liveIntervalId: number | null = null;
  private historyIntervalId: number | null = null;
  private liveRequestInFlight = false;
  private historyRequestInFlight = false;
  private destroyed = false;
  private readonly visibilityChangeListener = (): void => this.updatePolling();

  ngOnInit(): void {
    const tab = this.route.snapshot.queryParamMap.get('tab') === 'history' ? 'history' : 'live';
    this.selectedTab.set(tab);
    if (this.route.snapshot.queryParamMap.get('tab') !== tab) {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    }
    this.document.addEventListener('visibilitychange', this.visibilityChangeListener);
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.stopPolling();
      this.document.removeEventListener('visibilitychange', this.visibilityChangeListener);
    });
    this.updatePolling();
  }

  protected refresh(): void {
    if (this.selectedTab() === 'history') {
      void this.loadHistory();
      return;
    }

    void this.load();
  }

  protected selectTab(tab: 'live' | 'history'): void {
    if (this.selectedTab() === tab) return;

    this.selectedTab.set(tab);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
    });
    this.updatePolling();
  }

  protected scrollHistoryToLatest(): void {
    this.document.defaultView?.queueMicrotask(() => {
      const container = this.document.querySelector<HTMLElement>('.status-history__scroll');
      if (container !== null) container.scrollLeft = container.scrollWidth;
    });
  }

  protected formatDuration(milliseconds: number): string {
    const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000));
    const hours = Math.floor(totalSeconds / 3_600);
    const minutes = Math.floor((totalSeconds % 3_600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) return `${hours} h ${minutes} min ${seconds} s`;
    if (minutes > 0) return `${minutes} min ${seconds} s`;
    return `${seconds} s`;
  }

  protected taskStartedAt(task: WorkerTaskStatus): string {
    return task.workStartedAt ?? task.queuedAt;
  }

  protected workerUptime(startedAt: string): string {
    const elapsedMillis = Math.max(0, Date.now() - Date.parse(startedAt));
    const elapsedSeconds = Math.floor(elapsedMillis / 1_000);
    let value: number;
    let unit: Intl.RelativeTimeFormatUnit;
    if (elapsedSeconds < 60) {
      value = elapsedSeconds;
      unit = 'second';
    } else if (elapsedSeconds < 3_600) {
      value = Math.floor(elapsedSeconds / 60);
      unit = 'minute';
    } else if (elapsedSeconds < 86_400) {
      value = Math.floor(elapsedSeconds / 3_600);
      unit = 'hour';
    } else {
      value = Math.floor(elapsedSeconds / 86_400);
      unit = 'day';
    }

    return new Intl.RelativeTimeFormat(this.localization.locale(), { numeric: 'always' })
      .format(-value, unit);
  }

  private updatePolling(): void {
    if (this.document.visibilityState !== 'visible') {
      this.stopPolling();
      return;
    }

    this.stopPolling();
    if (this.selectedTab() === 'history') {
      void this.loadHistory();
      this.historyIntervalId = this.document.defaultView?.setInterval(
        () => void this.loadHistory(),
        HISTORY_REFRESH_INTERVAL_MILLIS,
      ) ?? null;
      return;
    }

    void this.load();
    this.liveIntervalId = this.document.defaultView?.setInterval(
      () => void this.load(),
      REFRESH_INTERVAL_MILLIS,
    ) ?? null;
  }

  private stopPolling(): void {
    if (this.liveIntervalId !== null) {
      this.document.defaultView?.clearInterval(this.liveIntervalId);
      this.liveIntervalId = null;
    }
    if (this.historyIntervalId !== null) {
      this.document.defaultView?.clearInterval(this.historyIntervalId);
      this.historyIntervalId = null;
    }
  }

  private async load(): Promise<void> {
    if (this.liveRequestInFlight || this.destroyed || this.document.visibilityState !== 'visible') return;

    this.liveRequestInFlight = true;
    this.refreshing.set(!this.loading());
    try {
      const workers = await this.api.status();
      if (this.destroyed) return;

      this.workers.set(
        [...workers].sort(
          (first, second) =>
            workerOrder(first) - workerOrder(second) || first.address.localeCompare(second.address),
        ),
      );
      this.lastUpdatedAt.set(new Date());
      this.error.set(null);
    } catch (error) {
      if (!this.destroyed) this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.liveRequestInFlight = false;
      if (!this.destroyed) {
        this.loading.set(false);
        this.refreshing.set(false);
      }
    }
  }

  private async loadHistory(): Promise<void> {
    if (this.historyRequestInFlight || this.destroyed || this.document.visibilityState !== 'visible') return;

    this.historyRequestInFlight = true;
    this.historyRefreshing.set(this.history() !== null);
    this.historyLoading.set(this.history() === null);
    try {
      this.history.set(await this.api.history());
      this.historyLastUpdatedAt.set(new Date());
      this.historyError.set(null);
    } catch (error) {
      if (!this.destroyed) this.historyError.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.historyRequestInFlight = false;
      if (!this.destroyed) {
        this.historyLoading.set(false);
        this.historyRefreshing.set(false);
      }
    }
  }

  protected historySeriesName(series: WorkerActivitySeries): string {
    const kind = this.localization.translate(series.kind === 'ALERT' ? 'status.history.alerts' : 'status.history.procedures');
    const instance = series.workerInstanceId.slice(0, 8);
    const worker = series.workerName || series.address || this.localization.translate('status.history.unknownWorker');
    return `${worker} (${instance}) - ${kind}`;
  }

  private createHistoryOptions(history: WorkerActivityHistory | null, locale: string): EChartsCoreOption {
    if (history === null) return {};

    const from = new Date(history.from);
    const minuteLabel = (minute: number): string => new Intl.DateTimeFormat(locale, {
      hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit',
    }).format(new Date(from.getTime() + minute * 60_000));
    const names = history.series.map((series) => this.historySeriesName(series));
    return {
      animation: false,
      color: HISTORY_COLORS,
      grid: { left: 68, right: 24, top: 66, bottom: 54, width: HISTORY_PLOT_WIDTH_PIXELS - 1 },
      legend: { data: names, type: 'scroll', top: 10, left: 12, right: 12 },
      tooltip: {
        trigger: 'axis',
        formatter: (items: readonly { value: readonly [number, number]; seriesName: string }[]) => {
          const minute = items[0]?.value[0] ?? 0;
          return [`<strong>${minuteLabel(minute)}</strong>`, ...items.map((item) => `${item.seriesName}: ${item.value[1]}`)].join('<br>');
        },
      },
      xAxis: {
        type: 'value', min: 0, max: HISTORY_MINUTES - 1, interval: 60,
        axisLabel: { formatter: (value: number) => minuteLabel(value), hideOverlap: true },
        splitLine: { show: true },
        name: this.localization.translate('status.history.timeAxis'),
        nameLocation: 'middle', nameGap: 34,
      },
      yAxis: {
        type: 'value', minInterval: 1,
        name: this.localization.translate('status.history.activeAxis'),
        nameLocation: 'middle', nameGap: 48,
      },
      series: history.series.map((series, index) => ({
        name: names[index], type: 'line', smooth: 0.25, smoothMonotone: 'x', showSymbol: false,
        lineStyle: { type: series.kind === 'PROCEDURE' ? 'dashed' : 'solid', width: 2 },
        data: series.values.map((value, minute) => [minute, value]),
      })),
    };
  }
}
