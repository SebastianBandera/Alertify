import { DashboardAlertCard } from './dashboard-card';

/** Visual state of a tile, derived from the last execution result. */
export type CardState = 'error' | 'warn' | 'success' | 'none';

export const CARD_STATE_ORDER: readonly CardState[] = ['error', 'warn', 'success', 'none'];

/**
 * Board preferences chosen on the ribbon. Hidden states and tags are stored
 * instead of the visible ones, so anything new shows up selected by default.
 */
export interface DashboardViewSettings {
  readonly hiddenStates: readonly CardState[];
  readonly hiddenTagIds: readonly number[];
  readonly ungroupGreens: boolean;
  readonly minified: boolean;
  readonly flatGreens: boolean;
  readonly hideIgnored: boolean;
}

export const DEFAULT_VIEW_SETTINGS: DashboardViewSettings = {
  hiddenStates: [],
  hiddenTagIds: [],
  ungroupGreens: false,
  minified: false,
  flatGreens: false,
  hideIgnored: false,
};

const STORAGE_KEY = 'alertify.dashboard.view';

function isCardState(value: unknown): value is CardState {
  return CARD_STATE_ORDER.some((state) => state === value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function booleanOr(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

/** An alert without tags always shows; one with tags shows while any of them is visible. */
export function hasVisibleTag(card: DashboardAlertCard, hiddenTagIds: readonly number[]): boolean {
  return card.alert.tags.length === 0 || card.alert.tags.some((tag) => !hiddenTagIds.includes(tag.id));
}

export function readStoredViewSettings(): DashboardViewSettings {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
    if (!isRecord(stored)) return DEFAULT_VIEW_SETTINGS;
    const hiddenStates = Array.isArray(stored['hiddenStates']) ? stored['hiddenStates'].filter(isCardState) : [];
    const hiddenTagIds = Array.isArray(stored['hiddenTagIds'])
      ? stored['hiddenTagIds'].filter((id): id is number => typeof id === 'number')
      : [];
    return {
      hiddenStates,
      hiddenTagIds,
      ungroupGreens: booleanOr(stored['ungroupGreens'], DEFAULT_VIEW_SETTINGS.ungroupGreens),
      minified: booleanOr(stored['minified'], DEFAULT_VIEW_SETTINGS.minified),
      flatGreens: booleanOr(stored['flatGreens'], DEFAULT_VIEW_SETTINGS.flatGreens),
      hideIgnored: booleanOr(stored['hideIgnored'], DEFAULT_VIEW_SETTINGS.hideIgnored),
    };
  } catch {
    return DEFAULT_VIEW_SETTINGS;
  }
}

export function storeViewSettings(settings: DashboardViewSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // The preferences still apply to this page when browser storage is unavailable.
  }
}
