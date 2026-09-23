import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';

import { DashboardAlertCard } from './dashboard-card';

const STORAGE_KEY = 'alertify.dashboard.muted';

interface MutedAlerts {
  /** Ignored until the user takes it back. */
  readonly ignored: ReadonlySet<number>;
  /** Silenced until the alert recovers (its last result is a success again). */
  readonly silenced: ReadonlySet<number>;
}

const NONE: MutedAlerts = { ignored: new Set(), silenced: new Set() };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function idSet(value: unknown): ReadonlySet<number> {
  return new Set(Array.isArray(value) ? value.filter((id): id is number => typeof id === 'number') : []);
}

function parse(raw: string | null): MutedAlerts {
  try {
    const stored: unknown = JSON.parse(raw ?? 'null');
    return isRecord(stored) ? { ignored: idSet(stored['ignored']), silenced: idSet(stored['silenced']) } : NONE;
  } catch {
    return NONE;
  }
}

/**
 * Alerts muted on this browser only: the backend keeps sending their tiles,
 * but the board deals them apart and they raise no notification. The choice
 * survives reloads through local storage and follows other tabs as it changes.
 */
@Injectable({ providedIn: 'root' })
export class DashboardMuteService {
  private readonly document = inject(DOCUMENT);
  private readonly muted = signal<MutedAlerts>(this.read());

  constructor() {
    const window = this.document.defaultView;
    if (!window) return;
    const onStorage = (event: StorageEvent): void => {
      if (event.key === STORAGE_KEY || event.key === null) this.muted.set(parse(event.newValue));
    };
    window.addEventListener('storage', onStorage);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', onStorage));
  }

  isMuted(alertId: number): boolean {
    return this.isIgnored(alertId) || this.isSilenced(alertId);
  }

  isIgnored(alertId: number): boolean {
    return this.muted().ignored.has(alertId);
  }

  isSilenced(alertId: number): boolean {
    return this.muted().silenced.has(alertId);
  }

  ignore(alertId: number): void {
    const { ignored, silenced } = this.muted();
    this.store({ ignored: new Set(ignored).add(alertId), silenced: this.without(silenced, alertId) });
  }

  silence(alertId: number): void {
    const { ignored, silenced } = this.muted();
    this.store({ ignored: this.without(ignored, alertId), silenced: new Set(silenced).add(alertId) });
  }

  unmute(alertId: number): void {
    const { ignored, silenced } = this.muted();
    this.store({ ignored: this.without(ignored, alertId), silenced: this.without(silenced, alertId) });
  }

  /** Lifts the silence of every given card that is green again. */
  releaseRecovered(cards: readonly DashboardAlertCard[]): void {
    const { ignored, silenced } = this.muted();
    const recovered = cards.filter((card) => silenced.has(card.alert.id) && card.lastExecution?.status === 'SUCCESS');
    if (recovered.length === 0) return;
    const next = new Set(silenced);
    for (const card of recovered) next.delete(card.alert.id);
    this.store({ ignored, silenced: next });
  }

  private without(ids: ReadonlySet<number>, alertId: number): ReadonlySet<number> {
    const next = new Set(ids);
    next.delete(alertId);
    return next;
  }

  private read(): MutedAlerts {
    try {
      return parse(localStorage.getItem(STORAGE_KEY));
    } catch {
      return NONE;
    }
  }

  private store(muted: MutedAlerts): void {
    this.muted.set(muted);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ ignored: [...muted.ignored], silenced: [...muted.silenced] }));
    } catch {
      // The choice still applies to this page when browser storage is unavailable.
    }
  }
}
