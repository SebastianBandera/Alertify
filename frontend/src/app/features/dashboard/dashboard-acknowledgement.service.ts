import { inject, Injectable, signal } from '@angular/core';

import { AlertApiService } from '../../core/api/alert-api.service';
import { DashboardAlertCard, pendingIssue, PendingIssue } from './dashboard-card';

/**
 * The current user's "seen" marks for alerts whose issues persist: one
 * instant per alert, kept on the server so they follow the user to any
 * browser. Tiles are the same for everyone; this is what makes an issue
 * pending for one user and already seen for another.
 */
@Injectable({ providedIn: 'root' })
export class DashboardAcknowledgementService {
  private readonly api = inject(AlertApiService);
  private readonly acknowledgedAt = signal<ReadonlyMap<number, string>>(new Map());
  private loadRun = 0;

  /** Reloads the marks, e.g. whenever the live channel (re)connects. A failure keeps the ones already known. */
  async load(): Promise<void> {
    const run = ++this.loadRun;
    try {
      const marks = await this.api.issueAcknowledgements();
      if (run === this.loadRun) this.acknowledgedAt.set(new Map(marks.map((mark) => [mark.alertId, mark.acknowledgedAt])));
    } catch {
      // Without the marks every persistent issue shows as pending, which errs on the side of attention.
    }
  }

  pending(card: DashboardAlertCard): PendingIssue | null {
    return pendingIssue(card, this.acknowledgedAt().get(card.alert.id));
  }

  /** Marks everything up to now as seen, optimistically; the previous mark comes back if the server refuses. */
  async acknowledge(alertId: number): Promise<void> {
    this.loadRun++;
    const previous = this.acknowledgedAt();
    this.acknowledgedAt.set(new Map(previous).set(alertId, new Date().toISOString()));
    try {
      const mark = await this.api.acknowledgeIssues(alertId);
      this.acknowledgedAt.update((marks) => new Map(marks).set(alertId, mark.acknowledgedAt));
    } catch (error) {
      this.acknowledgedAt.set(previous);
      throw error;
    }
  }
}
