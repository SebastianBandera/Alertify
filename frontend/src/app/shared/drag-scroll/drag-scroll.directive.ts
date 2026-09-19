import { DestroyRef, Directive, ElementRef, inject, signal } from '@angular/core';

/* Pointer travel below this is a click, above it a drag. */
const DRAG_THRESHOLD_PIXELS = 4;

/**
 * Turns a horizontally overflowing container into a click-and-drag strip:
 * pointer drags (and vertical wheel deltas) scroll it sideways, and a click
 * that ends a drag is swallowed so ancestors do not react to it. The host
 * gets `drag-scroll--overflowing` while there is hidden content and
 * `drag-scroll--dragging` during a drag, so styles can hint and react.
 */
@Directive({
  selector: '[appDragScroll]',
  host: {
    '[class.drag-scroll--overflowing]': 'overflowing()',
    '[class.drag-scroll--dragging]': 'dragging()',
    '(pointerdown)': 'startDrag($event)',
    '(pointermove)': 'drag($event)',
    '(pointerup)': 'endDrag($event)',
    '(pointercancel)': 'endDrag($event)',
    '(wheel)': 'scrollByWheel($event)',
  },
})
export class DragScrollDirective {
  private readonly element: HTMLElement = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  protected readonly overflowing = signal(false);
  protected readonly dragging = signal(false);
  private pointerId: number | null = null;
  private startX = 0;
  private startScrollLeft = 0;
  private dragged = false;

  constructor() {
    const resizeObserver = new ResizeObserver(() => this.updateOverflow());
    resizeObserver.observe(this.element);
    /* Capture phase, so the click never reaches the clickable card around the strip. */
    this.element.addEventListener('click', this.swallowClickAfterDrag, true);
    inject(DestroyRef).onDestroy(() => {
      resizeObserver.disconnect();
      this.element.removeEventListener('click', this.swallowClickAfterDrag, true);
    });
  }

  protected startDrag(event: PointerEvent): void {
    if (event.button !== 0 || !this.overflowing()) return;
    this.pointerId = event.pointerId;
    this.startX = event.clientX;
    this.startScrollLeft = this.element.scrollLeft;
    this.dragged = false;
  }

  protected drag(event: PointerEvent): void {
    if (event.pointerId !== this.pointerId) return;
    const deltaX = event.clientX - this.startX;
    if (!this.dragged && Math.abs(deltaX) < DRAG_THRESHOLD_PIXELS) return;
    if (!this.dragged) {
      /* Capture only once this is a drag: capturing on pointerdown would
         retarget the click of a plain tap to the strip instead of its child. */
      this.dragged = true;
      this.dragging.set(true);
      this.element.setPointerCapture(event.pointerId);
    }
    this.element.scrollLeft = this.startScrollLeft - deltaX;
  }

  protected endDrag(event: PointerEvent): void {
    if (event.pointerId !== this.pointerId) return;
    this.pointerId = null;
    this.dragging.set(false);
    /* A cancelled pointer never produces a click, so there is nothing to swallow. */
    if (event.type === 'pointercancel') this.dragged = false;
    if (this.element.hasPointerCapture(event.pointerId)) this.element.releasePointerCapture(event.pointerId);
  }

  protected scrollByWheel(event: WheelEvent): void {
    if (!this.overflowing() || event.deltaY === 0 || event.deltaX !== 0) return;
    event.preventDefault();
    this.element.scrollLeft += event.deltaY;
  }

  private readonly swallowClickAfterDrag = (event: MouseEvent): void => {
    if (!this.dragged) return;
    this.dragged = false;
    event.stopPropagation();
    event.preventDefault();
  };

  private updateOverflow(): void {
    this.overflowing.set(this.element.scrollWidth > this.element.clientWidth + 1);
  }
}
