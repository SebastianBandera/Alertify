import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { ResizableTableDirective } from './resizable-table.directive';
import { TableWidthPreferencesService } from './table-width-preferences.service';

@Component({
  imports: [ResizableTableDirective],
  template: `@if (visible()) {
    <div style="overflow-x:auto"><table appResizableTable="alerts" style="min-width:600px">
      @if (existingCols()) { <colgroup><col style="width:50%"><col style="width:50%"></colgroup> }
      <thead><tr><th data-column-id="name">{{ label() }}</th><th data-column-id="updatedAt"><button (click)="sorts = sorts + 1">Updated</button></th></tr></thead>
      <tbody><tr><td>Example</td><td>2026-10-20</td></tr></tbody>
    </table></div>
  }`,
})
class Host {
  readonly visible = signal(true);
  readonly existingCols = signal(false);
  readonly label = signal('Name');
  sorts = 0;
}

describe('ResizableTableDirective', () => {
  let fixture: ComponentFixture<Host>;
  let preferences: TableWidthPreferencesService;
  let capture: Set<number>;
  let naturalWidths: number[];
  let onResize: () => void;
  const locale = signal('en');
  const localization = {
    translateDynamic: (key: string) => ({
      'table.resize.column': locale() === 'en' ? 'Resize column {column}' : 'Redimensionar columna {column}',
      'table.resize.help': 'Resize {column}', 'table.resize.pixels': 'pixels',
    })[key] ?? key,
  };
  const table = () => fixture.nativeElement.querySelector('table') as HTMLTableElement;
  const handles = () => [...table().querySelectorAll<HTMLElement>('.table-column-resize')];
  const widths = () => [...table().querySelectorAll('col')].map(col => parseFloat(col.style.width));
  const pointer = (target: HTMLElement, type: string, x = 0, id = 1, extra = {}) => {
    const event = new Event(type, { bubbles: true, cancelable: true });
    Object.assign(event, { clientX: x, pointerId: id, button: 0, isPrimary: true, ...extra });
    target.dispatchEvent(event);
    return event;
  };
  const key = (target: HTMLElement, value: string) => target.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true }));

  beforeEach(async () => {
    localStorage.clear();
    locale.set('en');
    capture = new Set();
    naturalWidths = [200, 200];
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: () => void) { onResize = callback; }
      observe(): void {}
      disconnect(): void {}
    });
    Object.defineProperties(HTMLElement.prototype, {
      setPointerCapture: { configurable: true, value: vi.fn((id: number) => capture.add(id)) },
      hasPointerCapture: { configurable: true, value: (id: number) => capture.has(id) },
      releasePointerCapture: { configurable: true, value: vi.fn((id: number) => capture.delete(id)) },
    });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function(this: HTMLElement) {
      const colIndex = this.dataset['columnId'] === 'updatedAt' ? 1 : 0;
      const col = this.closest('table')?.querySelectorAll('col')[colIndex];
      const width = this.tagName === 'TH' ? (col?.style.width.endsWith('px') ? parseFloat(col.style.width) : naturalWidths[colIndex]) : 0;
      return { width, height: 40, x: 0, y: 0, top: 0, bottom: 40, left: 0, right: width, toJSON: () => ({}) };
    });
    await TestBed.configureTestingModule({ imports: [Host], providers: [
      { provide: AuthService, useValue: { userIdentifier: 'realm:user' } },
      { provide: LocalizationService, useValue: localization },
    ] }).compileComponents();
    preferences = TestBed.inject(TableWidthPreferencesService);
    fixture = TestBed.createComponent(Host);
  });
  afterEach(() => { fixture.destroy(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('preserves default sizing and leaves a click without movement unsaved', async () => {
    await render();
    expect(handles()).toHaveLength(2);
    expect(table().style.minWidth).toBe('600px');
    expect(table().querySelector('colgroup')).toBeNull();
    pointer(handles()[1], 'pointerdown', 400);
    pointer(handles()[1], 'pointerup', 400);
    expect(table().classList.contains('resizable-table--custom')).toBe(false);
    expect(localStorage.length).toBe(0);
  });

  it('captures every visible width, changes only the target, and persists on pointerup', async () => {
    await render();
    const handle = handles()[1];
    pointer(handle, 'pointerdown', 400);
    pointer(handle, 'pointermove', 480);
    expect(widths()).toEqual([200, 280]);
    expect(table().style.width).toBe('480px');
    expect(preferences.read('alerts', ['name', 'updatedAt'])).toBeNull();
    pointer(handle, 'pointerup', 480);
    expect(preferences.read('alerts', ['name', 'updatedAt'])).toEqual({ name: 200, updatedAt: 280 });
    expect(capture.size).toBe(0);
    expect(document.body.style.userSelect).toBe('');
  });

  it('clamps to 64 pixels and ignores unrelated pointers or non-primary clicks', async () => {
    await render();
    const handle = handles()[0];
    pointer(handle, 'pointerdown', 0, 2, { button: 2 });
    expect(capture.size).toBe(0);
    pointer(handle, 'pointerdown', 0);
    pointer(handle, 'pointermove', 30, 2);
    expect(table().querySelector('colgroup')).toBeNull();
    pointer(handle, 'pointermove', -1000);
    expect(widths()).toEqual([64, 200]);
    pointer(handle, 'pointerup');
  });

  it('accounts for horizontal scrolling during a drag', async () => {
    await render();
    pointer(handles()[0], 'pointerdown', 100);
    table().parentElement!.scrollLeft = 30;
    pointer(handles()[0], 'pointermove', 110);
    expect(widths()).toEqual([240, 200]);
  });

  it('raises naturally narrow columns to the minimum only on the first effective adjustment', async () => {
    naturalWidths = [53, 200];
    await render();
    expect(table().querySelector('colgroup')).toBeNull();
    pointer(handles()[1], 'pointerdown');
    pointer(handles()[1], 'pointermove', 30);
    pointer(handles()[1], 'pointerup', 30);
    expect(widths()).toEqual([64, 230]);
    expect(preferences.read('alerts', ['name', 'updatedAt'])).toEqual({ name: 64, updatedAt: 230 });
  });

  it('restores the complete default layout on Escape during the first drag', async () => {
    fixture.componentInstance.existingCols.set(true);
    await render();
    const originalStyle = table().getAttribute('style');
    pointer(handles()[0], 'pointerdown');
    pointer(handles()[0], 'pointermove', 40);
    key(handles()[0], 'Escape');
    expect(table().querySelectorAll('col')[0].style.width).toBe('50%');
    expect(table().getAttribute('style')).toBe(originalStyle);
    expect(table().classList.contains('resizable-table--custom')).toBe(false);
    expect(localStorage.length).toBe(0);
    expect(capture.size).toBe(0);
  });

  it.each(['pointercancel', 'lostpointercapture'])('rolls back a cancelled gesture: %s', async type => {
    preferences.save('alerts', { name: 220, updatedAt: 250 });
    await render();
    pointer(handles()[0], 'pointerdown', 0);
    pointer(handles()[0], 'pointermove', 60);
    pointer(handles()[0], type);
    expect(widths()).toEqual([220, 250]);
    expect(preferences.read('alerts', ['name', 'updatedAt'])).toEqual({ name: 220, updatedAt: 250 });
  });

  it('adjusts by keyboard, confirms with Enter or blur, and cancels with Escape', async () => {
    await render();
    const handle = handles()[0];
    key(handle, 'ArrowRight');
    key(handle, 'ArrowRight');
    expect(widths()).toEqual([220, 200]);
    expect(localStorage.length).toBe(0);
    key(handle, 'Escape');
    expect(table().querySelector('colgroup')).toBeNull();
    key(handle, 'ArrowLeft');
    key(handle, 'Enter');
    expect(preferences.read('alerts', ['name', 'updatedAt'])?.['name']).toBe(190);
    key(handle, 'ArrowRight');
    key(handle, 'Escape');
    expect(widths()[0]).toBe(190);
    key(handle, 'ArrowRight');
    handle.dispatchEvent(new Event('blur'));
    expect(preferences.read('alerts', ['name', 'updatedAt'])?.['name']).toBe(200);
  });

  it.each(['local', 'memory'])('restores after table recreation with %s storage and updates translated accessible labels', async storage => {
    if (storage === 'memory') vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Unavailable'); });
    await render();
    key(handles()[1], 'ArrowRight');
    key(handles()[1], 'Enter');
    fixture.componentInstance.visible.set(false);
    await render();
    fixture.componentInstance.visible.set(true);
    await render();
    expect(widths()).toEqual([200, 210]);
    locale.set('es-UY');
    fixture.componentInstance.label.set('Nombre');
    await render();
    expect(handles()[0].getAttribute('aria-label')).toBe('Redimensionar columna Nombre');
    expect(handles()[1].getAttribute('aria-valuenow')).toBe('210');
  });

  it('keeps header sorting separate from the separator', async () => {
    await render();
    handles()[1].click();
    expect(fixture.componentInstance.sorts).toBe(0);
    table().querySelector('button')!.click();
    expect(fixture.componentInstance.sorts).toBe(1);
  });

  it('updates accessible dimensions on window resizing without freezing or redistributing columns', async () => {
    await render();
    naturalWidths = [240, 240];
    onResize();
    expect(handles()[0].getAttribute('aria-valuenow')).toBe('240');
    expect(table().querySelector('colgroup')).toBeNull();
    key(handles()[0], 'ArrowRight');
    key(handles()[0], 'Enter');
    naturalWidths = [300, 300];
    onResize();
    expect(widths()).toEqual([250, 240]);
    expect(handles()[0].getAttribute('aria-valuenow')).toBe('250');
  });

  it('cleans up and discards an unfinished drag when loading removes the table', async () => {
    await render();
    document.body.style.cursor = 'wait';
    pointer(handles()[0], 'pointerdown');
    pointer(handles()[0], 'pointermove', 100);
    fixture.componentInstance.visible.set(false);
    await render();
    expect(capture.size).toBe(0);
    expect(document.body.style.cursor).toBe('wait');
    document.body.style.cursor = '';
    expect(localStorage.length).toBe(0);
  });
});
