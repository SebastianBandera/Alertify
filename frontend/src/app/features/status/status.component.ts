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

import {
  WorkerNodeStatus,
  WorkerStatusApiService,
  WorkerTaskStatus,
} from '../../core/api/worker-status-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

const REFRESH_INTERVAL_MILLIS = 5_000;

@Component({
  selector: 'app-status',
  imports: [DatePipe],
  templateUrl: './status.component.html',
  styleUrl: './status.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  private readonly api = inject(WorkerStatusApiService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly workers = signal<readonly WorkerNodeStatus[]>([]);
  protected readonly loading = signal(true);
  protected readonly refreshing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
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
  private intervalId: number | null = null;
  private requestInFlight = false;
  private destroyed = false;
  private readonly visibilityChangeListener = (): void => this.updatePolling();

  ngOnInit(): void {
    this.document.addEventListener('visibilitychange', this.visibilityChangeListener);
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.stopPolling();
      this.document.removeEventListener('visibilitychange', this.visibilityChangeListener);
    });
    this.updatePolling();
  }

  protected refresh(): void {
    void this.load();
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

  private updatePolling(): void {
    if (this.document.visibilityState !== 'visible') {
      this.stopPolling();
      return;
    }

    void this.load();
    if (this.intervalId === null) {
      this.intervalId = this.document.defaultView?.setInterval(
        () => void this.load(),
        REFRESH_INTERVAL_MILLIS,
      ) ?? null;
    }
  }

  private stopPolling(): void {
    if (this.intervalId === null) return;

    this.document.defaultView?.clearInterval(this.intervalId);
    this.intervalId = null;
  }

  private async load(): Promise<void> {
    if (this.requestInFlight || this.destroyed || this.document.visibilityState !== 'visible') return;

    this.requestInFlight = true;
    this.refreshing.set(!this.loading());
    try {
      const workers = await this.api.status();
      if (this.destroyed) return;

      this.workers.set(workers);
      this.lastUpdatedAt.set(new Date());
      this.error.set(null);
    } catch (error) {
      if (!this.destroyed) this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.requestInFlight = false;
      if (!this.destroyed) {
        this.loading.set(false);
        this.refreshing.set(false);
      }
    }
  }
}
