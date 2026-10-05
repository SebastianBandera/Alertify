import { afterRenderEffect, ChangeDetectionStrategy, Component, DestroyRef, effect, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import type { ECharts } from 'echarts';

import { ALERT_CHART_EXTRACTORS } from '../../core/alert-charts/alert-chart-extractors';
import { AlertApiService } from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { DashboardAlertCard } from './dashboard-card';

@Component({
  selector: 'app-alert-chart',
  template: `@if (supported() && (loading() || error() || data())) {
    <section><h3>{{ label() }}</h3>
      @if (loading()) { <p role="status">{{ localization.translateDynamic('chart.loading') }}</p> }
      @if (error()) { <p role="status">{{ localization.translateDynamic('chart.error') }}</p> }
      @if (data()) { <div #canvas class="chart" role="img" [attr.aria-label]="label()"></div> }
    </section>
  }`,
  styles: ['.chart { height: 260px; width: 100%; min-width: 0; }'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertChartComponent {
  readonly card = input.required<DashboardAlertCard>();
  protected readonly localization = inject(LocalizationService);
  private readonly api = inject(AlertApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly canvas = viewChild<ElementRef<HTMLElement>>('canvas');
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly data = signal<{
    readonly points: [number, number][];
    readonly from: number;
    readonly to: number;
    readonly echarts: typeof import('echarts');
  } | null>(null);
  protected readonly supported = (): boolean => !!ALERT_CHART_EXTRACTORS[this.card().alert.templateKey];
  protected readonly label = (): string => this.localization.translateDynamic(ALERT_CHART_EXTRACTORS[this.card().alert.templateKey]?.labelKey ?? 'chart.value');
  private chart: ECharts | null = null;
  private request = 0;

  constructor() {
    const resize = new ResizeObserver(() => this.chart?.resize());
    effect(() => { void this.load(this.card()); });
    afterRenderEffect(() => {
      const element = this.canvas()?.nativeElement;
      const data = this.data();
      resize.disconnect();
      this.chart?.dispose();
      this.chart = null;
      if (!element || !data) return;
      resize.observe(element);
      this.chart = data.echarts.init(element);
      this.chart.setOption({
        tooltip: { trigger: 'axis', renderMode: 'richText' },
        grid: { left: 65, right: 20, top: 20, bottom: 45 },
        xAxis: { type: 'time', min: data.from, max: data.to },
        yAxis: { type: 'value', name: ALERT_CHART_EXTRACTORS[this.card().alert.templateKey]?.unit ?? '', scale: true },
        series: [{ name: this.label(), type: 'line', data: data.points, showSymbol: data.points.length < 100, connectNulls: false }],
      });
    });
    this.destroyRef.onDestroy(() => { this.request++; resize.disconnect(); this.chart?.dispose(); });
  }

  private async load(card: DashboardAlertCard): Promise<void> {
    const request = ++this.request;
    this.data.set(null);
    this.loading.set(true);
    this.error.set(false);
    try {
      const definition = ALERT_CHART_EXTRACTORS[card.alert.templateKey];
      if (!definition) return;
      const to = new Date();
      const from = new Date(to.getTime() - card.historyWindowDays * 86_400_000);
      const executions = await this.api.chartExecutions(card.alert.id, from.toISOString(), to.toISOString());
      if (request !== this.request) return;
      const points: [number, number][] = [];
      for (const execution of executions.slice(0, 2000)) {
        if (execution.closed) continue;
        const timestamp = Date.parse(execution.finishedAt);
        const result = execution.statusMessage;
        if (!Number.isFinite(timestamp) || timestamp < from.getTime() || timestamp > to.getTime()
          || result === null || typeof result !== 'object' || Array.isArray(result)) continue;
        try {
          const value = definition.extract({ alert: card.alert, execution, result: result as Record<string, unknown> });
          if (typeof value === 'number' && Number.isFinite(value)) points.push([timestamp, value]);
        } catch { /* Custom extractors may reject individual historic result versions. */ }
      }
      points.sort((left, right) => left[0] - right[0]);
      if (points.length === 0) return;
      const echarts = await import('echarts');
      if (request !== this.request) return;
      this.data.set({ points, from: from.getTime(), to: to.getTime(), echarts });
    } catch {
      if (request === this.request) this.error.set(true);
    } finally {
      if (request === this.request) this.loading.set(false);
    }
  }
}
