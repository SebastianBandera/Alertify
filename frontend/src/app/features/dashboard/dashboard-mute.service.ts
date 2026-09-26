import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';

import { AlertMessageService } from '../../core/alert-messages/alert-message.service';
import { AlertExecutionStatus } from '../../core/api/alert-api.service';
import { DashboardAlertCard } from './dashboard-card';

const STORAGE_KEY = 'alertify.dashboard.muted';

export interface IgnoredStateResult {
  readonly executionId: string;
  readonly status: AlertExecutionStatus;
  readonly message: string;
  readonly identity: string;
}

export interface IgnoredStateComparison {
  readonly ignored: IgnoredStateResult;
  readonly current: IgnoredStateResult;
}

interface StateIgnore {
  readonly ignored: IgnoredStateResult;
  /** Present after a different semantic result automatically released the card. */
  readonly current?: IgnoredStateResult;
}

interface MutedAlerts {
  /** Ignored until the user takes it back. */
  readonly ignored: ReadonlySet<number>;
  /** Silenced until the alert recovers (its last result is a success again). */
  readonly silenced: ReadonlySet<number>;
  /** At most one state-aware ignore, active or released, per alert. */
  readonly stateIgnores: ReadonlyMap<number, StateIgnore>;
}

const NONE: MutedAlerts = { ignored: new Set(), silenced: new Set(), stateIgnores: new Map() };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function idSet(value: unknown): ReadonlySet<number> {
  return new Set(Array.isArray(value) ? value.filter((id): id is number => typeof id === 'number') : []);
}

function stateResult(value: unknown): IgnoredStateResult | null {
  if (!isRecord(value)) return null;
  const status = value['status'];
  if (typeof value['executionId'] !== 'string' || typeof value['message'] !== 'string' || typeof value['identity'] !== 'string') return null;
  if (status !== 'SUCCESS' && status !== 'WARN' && status !== 'ERROR') return null;
  return { executionId: value['executionId'], status, message: value['message'], identity: value['identity'] };
}

function stateIgnoreMap(value: unknown): ReadonlyMap<number, StateIgnore> {
  const result = new Map<number, StateIgnore>();
  if (!isRecord(value)) return result;
  for (const [rawId, rawState] of Object.entries(value)) {
    const id = Number(rawId);
    if (!Number.isInteger(id) || id <= 0 || !isRecord(rawState)) continue;
    const ignored = stateResult(rawState['ignored']);
    const current = rawState['current'] === undefined ? undefined : stateResult(rawState['current']);
    if (ignored === null || current === null) continue;
    result.set(id, current === undefined ? { ignored } : { ignored, current });
  }
  return result;
}

function parse(raw: string | null): MutedAlerts {
  try {
    const stored: unknown = JSON.parse(raw ?? 'null');
    return isRecord(stored)
      ? { ignored: idSet(stored['ignored']), silenced: idSet(stored['silenced']), stateIgnores: stateIgnoreMap(stored['stateIgnores']) }
      : NONE;
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
  private readonly messages = inject(AlertMessageService);
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
    return this.muted().ignored.has(alertId) || this.isStateIgnored(alertId);
  }

  isStateIgnored(alertId: number): boolean {
    const state = this.muted().stateIgnores.get(alertId);
    return state !== undefined && state.current === undefined;
  }

  isSilenced(alertId: number): boolean {
    return this.muted().silenced.has(alertId);
  }

  stateComparison(alertId: number): IgnoredStateComparison | null {
    const state = this.muted().stateIgnores.get(alertId);
    return state?.current === undefined ? null : { ignored: state.ignored, current: state.current };
  }

  ignore(alertId: number): void {
    const { ignored, silenced } = this.muted();
    this.store({
      ignored: new Set(ignored).add(alertId),
      silenced: this.without(silenced, alertId),
      stateIgnores: this.withoutActiveStateIgnore(alertId),
    });
  }

  ignoreState(card: DashboardAlertCard): void {
    const execution = card.lastExecution;
    if (execution === null || (execution.status !== 'WARN' && execution.status !== 'ERROR')) return;
    const presentation = this.messages.presentation(card.alert.templateKey, execution);
    const stateIgnores = new Map(this.muted().stateIgnores);
    stateIgnores.set(card.alert.id, {
      ignored: {
        executionId: execution.executionId,
        status: execution.status,
        message: presentation.text,
        identity: presentation.identity,
      },
    });
    const { ignored, silenced } = this.muted();
    this.store({ ignored: this.without(ignored, card.alert.id), silenced: this.without(silenced, card.alert.id), stateIgnores });
  }

  silence(alertId: number): void {
    const { ignored, silenced } = this.muted();
    this.store({
      ignored: this.without(ignored, alertId),
      silenced: new Set(silenced).add(alertId),
      stateIgnores: this.withoutActiveStateIgnore(alertId),
    });
  }

  unmute(alertId: number): void {
    const { ignored, silenced } = this.muted();
    const stateIgnores = new Map(this.muted().stateIgnores);
    if (this.isStateIgnored(alertId)) stateIgnores.delete(alertId);
    this.store({ ignored: this.without(ignored, alertId), silenced: this.without(silenced, alertId), stateIgnores });
  }

  /** Releases state-aware ignores whose status or semantic localized result changed. */
  releaseChangedStates(cards: readonly DashboardAlertCard[]): ReadonlySet<number> {
    const { ignored, silenced } = this.muted();
    const stateIgnores = new Map(this.muted().stateIgnores);
    const released = new Set<number>();
    for (const card of cards) {
      const state = stateIgnores.get(card.alert.id);
      const execution = card.lastExecution;
      if (state === undefined || state.current !== undefined || execution === null || execution.executionId === state.ignored.executionId) continue;
      const presentation = this.messages.presentation(card.alert.templateKey, execution);
      if (execution.status === state.ignored.status && presentation.identity === state.ignored.identity) continue;
      stateIgnores.set(card.alert.id, {
        ignored: state.ignored,
        current: {
          executionId: execution.executionId,
          status: execution.status,
          message: presentation.text,
          identity: presentation.identity,
        },
      });
      released.add(card.alert.id);
    }
    if (released.size > 0) this.store({ ignored, silenced, stateIgnores });
    return released;
  }

  /** Lifts the silence of every given card that is green again. */
  releaseRecovered(cards: readonly DashboardAlertCard[]): void {
    const { ignored, silenced, stateIgnores } = this.muted();
    const recovered = cards.filter((card) => silenced.has(card.alert.id) && card.lastExecution?.status === 'SUCCESS');
    if (recovered.length === 0) return;
    const next = new Set(silenced);
    for (const card of recovered) next.delete(card.alert.id);
    this.store({ ignored, silenced: next, stateIgnores });
  }

  private without(ids: ReadonlySet<number>, alertId: number): ReadonlySet<number> {
    const next = new Set(ids);
    next.delete(alertId);
    return next;
  }

  private withoutActiveStateIgnore(alertId: number): ReadonlyMap<number, StateIgnore> {
    const stateIgnores = new Map(this.muted().stateIgnores);
    if (this.isStateIgnored(alertId)) stateIgnores.delete(alertId);
    return stateIgnores;
  }

  private read(): MutedAlerts {
    try {
      return parse(this.document.defaultView?.localStorage.getItem(STORAGE_KEY) ?? null);
    } catch {
      return NONE;
    }
  }

  private store(muted: MutedAlerts): void {
    this.muted.set(muted);
    try {
      const stateIgnores = Object.fromEntries([...muted.stateIgnores].map(([id, state]) => [String(id), state]));
      this.document.defaultView?.localStorage.setItem(STORAGE_KEY, JSON.stringify({
        ignored: [...muted.ignored], silenced: [...muted.silenced], stateIgnores,
      }));
    } catch {
      // The choice still applies to this page when browser storage is unavailable.
    }
  }
}
