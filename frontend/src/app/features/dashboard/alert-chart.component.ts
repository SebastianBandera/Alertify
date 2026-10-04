import { afterRenderEffect, ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import type { ECharts } from 'echarts';

import { ALERT_CHART_EXTRACTORS } from '../../core/alert-charts/alert-chart-extractors';
import { AlertApiService } from '../../core/api/alert-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { DashboardAlertCard } from './dashboard-card';

@Component({
  selector: 'app-alert-chart',
  template: `@if (supported()) {
    <section><h3>{{ label() }}</h3>
      @if (loading()) { <p role="status">{{ localization.translateDynamic('chart.loading') }}</p> }
      @if (error()) { <p role="status">{{ localization.translateDynamic('chart.error') }}</p> }
      @if (!loading() && !error() && empty()) { <p>{{ localization.translateDynamic('chart.empty') }}</p> }
      <div #canvas class="chart" role="img" [attr.aria-label]="label()"></div>
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
  protected readonly empty = signal(false);
  protected readonly supported = (): boolean => !!ALERT_CHART_EXTRACTORS[this.card().alert.templateKey];
  protected readonly label = (): string => this.localization.translateDynamic(ALERT_CHART_EXTRACTORS[this.card().alert.templateKey]?.labelKey ?? 'chart.value');
  private chart: ECharts | null = null;
  private request = 0;
  private loadedId: number | null = null;

  constructor() {
    const resize = new ResizeObserver(() => this.chart?.resize());
    afterRenderEffect(() => {
      const element = this.canvas()?.nativeElement;
      const card = this.card();
      if (!element || this.loadedId === card.alert.id) return;
      this.loadedId = card.alert.id;
      resize.observe(element);
      void this.load(card, element);
    });
    this.destroyRef.onDestroy(() => { this.request++; resize.disconnect(); this.chart?.dispose(); });
  }

  private async load(card: DashboardAlertCard, element: HTMLElement): Promise<void> {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set(false);
    try {
      const definition = ALERT_CHART_EXTRACTORS[card.alert.templateKey];
      const to = new Date();
      const from = new Date(to.getTime() - card.historyWindowDays * 86_400_000);
      const [executions, echarts] = await Promise.all([
        this.api.chartExecutions(card.alert.id, from.toISOString(), to.toISOString()),
        import('echarts'),
      ]);
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
      this.empty.set(points.length === 0);
      this.chart?.dispose();
      this.chart = echarts.init(element);
      this.chart.setOption({
        tooltip: { trigger: 'axis', renderMode: 'richText' },
        grid: { left: 65, right: 20, top: 20, bottom: 45 },
        xAxis: { type: 'time', min: from.getTime(), max: to.getTime() },
        yAxis: { type: 'value', name: definition.unit ?? '', scale: true },
        series: [{ name: this.label(), type: 'line', data: points, showSymbol: points.length < 100, connectNulls: false }],
      });
    } catch {
      if (request === this.request) this.error.set(true);
    } finally {
      if (request === this.request) this.loading.set(false);
    }
  }
}
