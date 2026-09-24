import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output, signal } from '@angular/core';

export interface SearchableSelectOption {
  readonly value: number;
  readonly label: string;
  readonly description?: string;
  readonly searchText?: string;
}

let searchableSelectId = 0;

@Component({
  selector: 'app-searchable-select',
  templateUrl: './searchable-select.component.html',
  styleUrl: './searchable-select.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SearchableSelectComponent {
  readonly options = input<readonly SearchableSelectOption[]>([]);
  readonly value = input<number | null>(null);
  readonly placeholder = input('');
  readonly emptyLabel = input('');
  readonly clearLabel = input('');
  readonly disabled = input(false);
  readonly ariaLabel = input('');
  readonly valueChange = output<number | null>();

  protected readonly open = signal(false);
  protected readonly query = signal('');
  protected readonly activeIndex = signal(-1);
  protected readonly listboxId = `searchable-select-${searchableSelectId++}`;
  protected readonly selected = computed(() =>
    this.options().find((option) => option.value === this.value()) ?? null,
  );
  protected readonly visibleOptions = computed(() => {
    const query = this.normalize(this.query());
    if (!query) return this.options();
    return this.options().filter((option) =>
      this.normalize(`${option.label} ${option.description ?? ''} ${option.searchText ?? ''}`).includes(query),
    );
  });
  private readonly elementRef = inject(ElementRef<HTMLElement>);

  protected displayValue(): string {
    return this.open() ? this.query() : this.selected()?.label ?? '';
  }

  protected showOptions(): void {
    if (this.disabled()) return;
    this.query.set('');
    this.open.set(true);
    const selectedIndex = this.visibleOptions().findIndex((option) => option.value === this.value());
    this.activeIndex.set(selectedIndex >= 0 ? selectedIndex : 0);
  }

  protected updateQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.open.set(true);
    this.activeIndex.set(this.visibleOptions().length ? 0 : -1);
  }

  protected select(option: SearchableSelectOption): void {
    this.valueChange.emit(option.value);
    this.query.set('');
    this.open.set(false);
    this.activeIndex.set(-1);
  }

  protected clear(event: MouseEvent): void {
    event.stopPropagation();
    this.valueChange.emit(null);
    this.query.set('');
    this.open.set(false);
    this.activeIndex.set(-1);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.open.set(false);
      this.query.set('');
      this.activeIndex.set(-1);
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!this.open()) this.showOptions();
      const options = this.visibleOptions();
      if (!options.length) return;
      const direction = event.key === 'ArrowDown' ? 1 : -1;
      this.activeIndex.update((index) => (index + direction + options.length) % options.length);
      return;
    }
    if (event.key === 'Enter' && this.open()) {
      const option = this.visibleOptions()[this.activeIndex()];
      if (option) {
        event.preventDefault();
        this.select(option);
      }
    }
  }

  protected onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.elementRef.nativeElement.contains(next)) return;
    this.open.set(false);
    this.query.set('');
    this.activeIndex.set(-1);
  }

  private normalize(value: string): string {
    return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase().trim();
  }
}
