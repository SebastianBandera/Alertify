import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AlertTag } from '../../../core/api/alert-api.service';
import { LocalizationService } from '../../../core/i18n/localization.service';
import { TranslationKey } from '../../../core/i18n/localization.types';
import { DragScrollDirective } from '../../../shared/drag-scroll/drag-scroll.directive';
import { CARD_STATE_ORDER, CardState, DashboardViewSettings } from '../dashboard-view-settings';

type RibbonMenu = 'states' | 'tags' | 'advanced';
type AdvancedOption = 'ungroupGreens' | 'minified' | 'flatGreens' | 'hideIgnored';

const STATE_LABEL_KEYS: Readonly<Record<CardState, TranslationKey>> = {
  error: 'dashboard.status.ERROR',
  warn: 'dashboard.status.WARN',
  success: 'dashboard.status.SUCCESS',
  none: 'dashboard.status.none',
};

const ADVANCED_OPTIONS: readonly { readonly key: AdvancedOption; readonly labelKey: TranslationKey }[] = [
  { key: 'ungroupGreens', labelKey: 'dashboard.ribbon.ungroupGreens' },
  { key: 'minified', labelKey: 'dashboard.ribbon.minified' },
  { key: 'flatGreens', labelKey: 'dashboard.ribbon.flatGreens' },
  { key: 'hideIgnored', labelKey: 'dashboard.ribbon.hideIgnored' },
];

/**
 * Thin strip above the board: three menus whose options unfold to the right
 * inside the strip itself. Dimmed options are hidden from the board.
 */
@Component({
  selector: 'app-dashboard-ribbon',
  imports: [FormsModule, DragScrollDirective],
  templateUrl: './dashboard-ribbon.component.html',
  styleUrl: './dashboard-ribbon.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardRibbonComponent {
  protected readonly localization = inject(LocalizationService);
  private readonly injector = inject(Injector);
  readonly settings = model.required<DashboardViewSettings>();
  readonly tags = input.required<readonly AlertTag[]>();
  readonly searchText = model.required<string>();
  protected readonly openMenu = signal<RibbonMenu | null>(null);
  protected readonly searchOpen = signal(false);
  protected readonly states = CARD_STATE_ORDER;
  protected readonly advancedOptions = ADVANCED_OPTIONS;
  protected readonly visibleStateCount = computed(() => this.states.length - this.settings().hiddenStates.length);
  protected readonly visibleTagCount = computed(() => {
    const hidden = new Set(this.settings().hiddenTagIds);
    return this.tags().filter((tag) => !hidden.has(tag.id)).length;
  });
  protected readonly activeAdvancedCount = computed(() =>
    this.advancedOptions.filter((option) => this.settings()[option.key]).length);
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  protected toggleMenu(menu: RibbonMenu): void {
    this.openMenu.update((current) => (current === menu ? null : menu));
  }

  protected stateLabel(state: CardState): string {
    return this.localization.translate(STATE_LABEL_KEYS[state]);
  }

  protected isStateVisible(state: CardState): boolean {
    return !this.settings().hiddenStates.includes(state);
  }

  protected toggleState(state: CardState): void {
    this.settings.update((settings) => ({
      ...settings,
      hiddenStates: settings.hiddenStates.includes(state)
        ? settings.hiddenStates.filter((hidden) => hidden !== state)
        : [...settings.hiddenStates, state],
    }));
  }

  protected isTagVisible(tag: AlertTag): boolean {
    return !this.settings().hiddenTagIds.includes(tag.id);
  }

  protected toggleTag(tag: AlertTag): void {
    this.settings.update((settings) => ({
      ...settings,
      hiddenTagIds: settings.hiddenTagIds.includes(tag.id)
        ? settings.hiddenTagIds.filter((hidden) => hidden !== tag.id)
        : [...settings.hiddenTagIds, tag.id],
    }));
  }

  protected toggleAdvanced(option: AdvancedOption): void {
    this.settings.update((settings) => ({ ...settings, [option]: !settings[option] }));
  }

  protected toggleSearch(): void {
    if (this.searchOpen()) {
      this.closeSearch();
      return;
    }
    this.openMenu.set(null);
    this.searchOpen.set(true);
    afterNextRender(() => this.searchInput()?.nativeElement.focus(), { injector: this.injector });
  }

  protected closeSearch(): void {
    this.searchOpen.set(false);
    this.searchText.set('');
  }

  protected closeSearchFromKeyboard(event: Event): void {
    event.preventDefault();
    const search = (event.currentTarget as HTMLElement).closest('.ribbon__search');
    search?.querySelector<HTMLButtonElement>('.ribbon__search-toggle')?.focus();
    this.closeSearch();
  }
}
