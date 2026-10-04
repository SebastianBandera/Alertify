import { DOCUMENT } from '@angular/common';
import { Injectable, Injector, afterNextRender, inject } from '@angular/core';

interface EditorViewportSnapshot {
  readonly scrollX: number;
  readonly scrollY: number;
  readonly focusTarget: HTMLElement | null;
  readonly focusKey: string;
}

/** Preserves the list viewport while an item is edited in a modal dialog. */
@Injectable()
export class EditorViewportService {
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private snapshot: EditorViewportSnapshot | null = null;

  capture(focusKey: string): void {
    const window = this.document.defaultView;
    if (!window) return;
    const activeElement = this.document.activeElement;
    this.snapshot = {
      scrollX: window.scrollX,
      scrollY: window.scrollY,
      focusTarget: activeElement instanceof HTMLElement ? activeElement : null,
      focusKey,
    };
  }

  restore(): void {
    const snapshot = this.snapshot;
    this.snapshot = null;
    if (snapshot === null) return;

    afterNextRender(() => {
      const window = this.document.defaultView;
      if (!window) return;
      window.scrollTo(snapshot.scrollX, snapshot.scrollY);
      const focusTarget = snapshot.focusTarget?.isConnected
        ? snapshot.focusTarget
        : Array.from(this.document.querySelectorAll<HTMLElement>('[data-editor-viewport]'))
          .find((element) => element.dataset['editorViewport'] === snapshot.focusKey) ?? null;
      focusTarget?.focus({ preventScroll: true });
    }, { injector: this.injector });
  }
}
