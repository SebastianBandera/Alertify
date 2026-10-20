import { DestroyRef, Directive, ElementRef, afterNextRender, inject, input } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';
import { columnMinimum } from './column-minimum';
import { ColumnWidths, MAX_COLUMN_WIDTH, MIN_COLUMN_WIDTH, TableWidthPreferencesService } from './table-width-preferences.service';

interface Column {
  readonly id: string;
  readonly header: HTMLTableCellElement;
  readonly handle: HTMLElement;
  readonly minimum: number;
}

interface Gesture {
  readonly index: number;
  readonly before: number[] | null;
  readonly visible: number[];
  readonly pointerId: number | null;
  readonly startX: number;
  readonly startScroll: number;
}

@Directive({ selector: 'table[appResizableTable]' })
export class ResizableTableDirective {
  readonly tableId = input.required<string>({ alias: 'appResizableTable' });
  private readonly table = inject<ElementRef<HTMLTableElement>>(ElementRef).nativeElement;
  private readonly preferences = inject(TableWidthPreferencesService);
  private readonly localization = inject(LocalizationService);
  private readonly destroyRef = inject(DestroyRef);
  private columns: Column[] = [];
  private widths: number[] | null = null;
  private gesture: Gesture | null = null;
  private colgroup: HTMLTableColElement | null = null;
  private createdColgroup = false;
  private originalTableStyle: string | null = null;
  private originalColStyles: (string | null)[] = [];
  private originalUserSelect = '';
  private originalCursor = '';
  private readonly cleanups: (() => void)[] = [];

  constructor() {
    afterNextRender(() => this.initialize());
    this.destroyRef.onDestroy(() => {
      this.finish(false);
      for (const cleanup of this.cleanups) cleanup();
    });
  }

  private initialize(): void {
    const headers = Array.from(this.table.tHead?.rows[0]?.cells ?? []);
    const ids = headers.map(header => header.dataset['columnId'] ?? '');
    if (!ids.length || ids.some(id => !id) || new Set(ids).size !== ids.length) return;

    this.originalTableStyle = this.table.getAttribute('style');
    this.colgroup = this.table.querySelector(':scope > colgroup');
    this.originalColStyles = Array.from(this.colgroup?.children ?? []).map(col => col.getAttribute('style'));
    this.columns = headers.map((header, index) => {
      const handle = this.table.ownerDocument.createElement('span');
      handle.className = 'table-column-resize';
      handle.tabIndex = 0;
      handle.setAttribute('role', 'separator');
      handle.setAttribute('aria-orientation', 'vertical');
      header.classList.add('resizable-table__heading');
      header.append(handle);
      const column = { id: ids[index], header, handle, minimum: columnMinimum(this.table, header, index) };
      this.listen(handle, 'pointerdown', event => this.startPointer(event as PointerEvent, index));
      this.listen(handle, 'pointermove', event => this.movePointer(event as PointerEvent));
      this.listen(handle, 'pointerup', event => {
        if ((event as PointerEvent).pointerId === this.gesture?.pointerId) this.finish(true);
      });
      this.listen(handle, 'pointercancel', () => this.finish(false));
      this.listen(handle, 'lostpointercapture', () => this.finish(false));
      this.listen(handle, 'keydown', event => this.keydown(event as KeyboardEvent, index));
      this.listen(handle, 'blur', () => { if (this.gesture?.pointerId === null) this.finish(true); });
      this.listen(handle, 'click', event => { event.preventDefault(); event.stopPropagation(); });
      return column;
    });
    const saved = this.preferences.read(this.tableId(), ids);
    if (saved && this.columns.every(column => saved[column.id] >= column.minimum)) {
      this.apply(this.columns.map(column => saved[column.id]));
    }

    this.updateLabels();
    const observer = new MutationObserver(() => this.updateLabels());
    observer.observe(this.table.tHead!, { subtree: true, childList: true, characterData: true });
    this.cleanups.push(() => observer.disconnect());
    const resizeObserver = new ResizeObserver(() => this.updateLabels());
    resizeObserver.observe(this.table);
    this.cleanups.push(() => resizeObserver.disconnect());
    this.listen(this.table.ownerDocument, 'keydown', event => {
      if ((event as KeyboardEvent).key === 'Escape' && this.gesture) {
        event.preventDefault();
        event.stopPropagation();
        this.finish(false);
      }
    });
  }

  private startPointer(event: PointerEvent, index: number): void {
    if (event.button !== 0 || !event.isPrimary) return;

    event.preventDefault();
    event.stopPropagation();
    this.finish(true);
    this.columns[index].handle.focus({ preventScroll: true });
    this.begin(index, event.pointerId, event.clientX);
    const bodyStyle = this.table.ownerDocument.body.style;
    this.originalUserSelect = bodyStyle.userSelect;
    this.originalCursor = bodyStyle.cursor;
    bodyStyle.userSelect = 'none';
    bodyStyle.cursor = 'col-resize';
    this.table.classList.add('resizable-table--dragging');
    this.columns[index].handle.setPointerCapture(event.pointerId);
  }

  private movePointer(event: PointerEvent): void {
    const gesture = this.gesture;
    if (!gesture || gesture.pointerId !== event.pointerId) return;

    event.preventDefault();
    const delta = event.clientX - gesture.startX + (this.table.parentElement!.scrollLeft - gesture.startScroll);
    this.resize(gesture.visible[gesture.index] + delta);
  }

  private keydown(event: KeyboardEvent, index: number): void {
    if (event.key === 'Escape' && this.gesture) {
      event.preventDefault();
      event.stopPropagation();
      this.finish(false);
      return;
    }
    if (event.key === 'Enter' && this.gesture?.pointerId === null) {
      event.preventDefault();
      event.stopPropagation();
      this.finish(true);
      return;
    }
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key) || this.gesture?.pointerId != null) return;

    event.preventDefault();
    event.stopPropagation();
    if (!this.gesture) this.begin(index, null, 0);
    const current = this.widths?.[index] ?? this.gesture!.visible[index];
    this.resize(current + (event.key === 'ArrowRight' ? 10 : -10));
  }

  private begin(index: number, pointerId: number | null, startX: number): void {
    this.gesture = {
      index, pointerId, startX, before: this.widths ? [...this.widths] : null,
      visible: this.columns.map(column => column.header.getBoundingClientRect().width),
      startScroll: this.table.parentElement!.scrollLeft,
    };
  }

  private resize(width: number): void {
    const gesture = this.gesture!;
    const next = this.clamp(width, this.columns[gesture.index].minimum);
    if (Math.abs(next - gesture.visible[gesture.index]) < .01 && !this.widths) return;

    const widths = [...(this.widths ?? gesture.visible.map((width, index) => this.clamp(width, this.columns[index].minimum)))];
    widths[gesture.index] = next;
    this.apply(widths);
  }

  private finish(commit: boolean): void {
    const gesture = this.gesture;
    if (!gesture) return;

    this.gesture = null;
    if (!commit) {
      if (gesture.before) this.apply(gesture.before);
      else this.restoreDefault();
    } else if (this.widths && this.widths.some((width, index) => Math.abs(width - (gesture.before ?? gesture.visible)[index]) > .01)) {
      const widths: ColumnWidths = Object.fromEntries(this.columns.map((column, index) => [column.id, this.widths![index]]));
      this.preferences.save(this.tableId(), widths);
    } else if (!gesture.before) {
      this.restoreDefault();
    }
    if (gesture.pointerId !== null) {
      const handle = this.columns[gesture.index].handle;
      if (handle.hasPointerCapture(gesture.pointerId)) handle.releasePointerCapture(gesture.pointerId);
      const bodyStyle = this.table.ownerDocument.body.style;
      bodyStyle.userSelect = this.originalUserSelect;
      bodyStyle.cursor = this.originalCursor;
      this.table.classList.remove('resizable-table--dragging');
    }
    this.updateLabels();
  }

  private apply(widths: number[]): void {
    if (!this.colgroup) {
      this.colgroup = this.table.ownerDocument.createElement('colgroup');
      this.table.insertBefore(this.colgroup, this.table.tHead);
      this.createdColgroup = true;
    }
    while (this.colgroup.children.length < this.columns.length) this.colgroup.append(this.table.ownerDocument.createElement('col'));
    this.widths = [...widths];
    this.table.classList.add('resizable-table--custom');
    this.table.style.width = `${widths.reduce((sum, width) => sum + width, 0)}px`;
    widths.forEach((width, index) => (this.colgroup!.children[index] as HTMLElement).style.width = `${width}px`);
    this.updateLabels();
  }

  private restoreDefault(): void {
    this.widths = null;
    this.table.classList.remove('resizable-table--custom');
    if (this.originalTableStyle === null) this.table.removeAttribute('style');
    else this.table.setAttribute('style', this.originalTableStyle);

    if (this.createdColgroup) {
      this.colgroup?.remove();
      this.colgroup = null;
      this.createdColgroup = false;
    } else {
      Array.from(this.colgroup?.children ?? []).forEach((col, index) => {
        const style = this.originalColStyles[index];
        if (style == null) col.removeAttribute('style');
        else col.setAttribute('style', style);
      });
    }
  }

  private updateLabels(): void {
    const pixels = this.localization.translateDynamic('table.resize.pixels');
    for (const [index, column] of this.columns.entries()) {
      const label = column.header.textContent?.replace(/\s+/g, ' ').trim() ?? column.id;
      const description = this.localization.translateDynamic('table.resize.column').replace('{column}', label);
      const width = Math.round(this.widths?.[index] ?? column.header.getBoundingClientRect().width);
      column.handle.setAttribute('aria-label', description);
      column.handle.setAttribute('title', this.localization.translateDynamic('table.resize.help').replace('{column}', label));
      column.handle.setAttribute('aria-valuemin', String(column.minimum));
      column.handle.setAttribute('aria-valuemax', String(MAX_COLUMN_WIDTH));
      column.handle.setAttribute('aria-valuenow', String(width));
      column.handle.setAttribute('aria-valuetext', `${width} ${pixels}`);
    }
  }

  private clamp(width: number, minimum: number): number {
    return Math.min(MAX_COLUMN_WIDTH, Math.max(MIN_COLUMN_WIDTH, minimum, width));
  }

  private listen(target: EventTarget, type: string, listener: EventListener): void {
    target.addEventListener(type, listener);
    this.cleanups.push(() => target.removeEventListener(type, listener));
  }
}
