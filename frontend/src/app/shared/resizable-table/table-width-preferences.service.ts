import { Injectable, inject } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';

export const MIN_COLUMN_WIDTH = 64;
export const MAX_COLUMN_WIDTH = 100000;
export type ColumnWidths = Readonly<Record<string, number>>;

interface StoredWidths {
  readonly version: 1;
  readonly widths: ColumnWidths;
}

@Injectable({ providedIn: 'root' })
export class TableWidthPreferencesService {
  private readonly auth = inject(AuthService);
  // A failed write must not allow an older disk value to replace the session's choice.
  private readonly pending = new Map<string, StoredWidths>();

  read(tableId: string, columnIds: readonly string[]): ColumnWidths | null {
    const key = this.key(tableId);
    if (!key) return null;

    try {
      const stored: unknown = this.pending.get(key) ?? JSON.parse(localStorage.getItem(key) ?? 'null');
      if (!this.valid(stored, columnIds)) return null;

      return { ...stored.widths };
    } catch {
      return null;
    }
  }

  save(tableId: string, widths: ColumnWidths): void {
    const key = this.key(tableId);
    const stored: StoredWidths = { version: 1, widths: { ...widths } };
    if (!key || !this.valid(stored, Object.keys(widths))) return;

    this.pending.set(key, stored);
    try {
      localStorage.setItem(key, JSON.stringify(stored));
      this.pending.delete(key);
    } catch {
      // The root service retains the choice while tables are recreated.
    }
  }

  private key(tableId: string): string | null {
    const user = this.auth.userIdentifier;
    if (!user || user.endsWith(':unknown')) return null;

    return `alertify.table-widths.v1.${encodeURIComponent(user)}.${tableId}`;
  }

  private valid(value: unknown, ids: readonly string[]): value is StoredWidths {
    if (!value || typeof value !== 'object') return false;

    const stored = value as Partial<StoredWidths>;
    if (stored.version !== 1 || !stored.widths || typeof stored.widths !== 'object' || Array.isArray(stored.widths)) return false;

    const keys = Object.keys(stored.widths);
    return ids.length > 0 && new Set(ids).size === ids.length && keys.length === ids.length
      && ids.every(id => Object.hasOwn(stored.widths!, id)
        && typeof stored.widths![id] === 'number' && Number.isFinite(stored.widths![id])
        && stored.widths![id] >= MIN_COLUMN_WIDTH && stored.widths![id] <= MAX_COLUMN_WIDTH);
  }
}
