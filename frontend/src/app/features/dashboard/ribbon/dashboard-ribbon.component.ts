import { ChangeDetectionStrategy, Component, computed, inject, input, model, signal } from '@angular/core';

import { AlertTag } from '../../../core/api/alert-api.service';
import { LocalizationService } from '../../../core/i18n/localization.service';
import { TranslationKey } from '../../../core/i18n/localization.types';
import { DragScrollDirective } from '../../../shared/drag-scroll/drag-scroll.directive';
import { CARD_STATE_ORDER, CardState, DashboardViewSettings } from '../dashboard-view-settings';

type RibbonMenu = 'states' | 'tags' | 'advanced';
type AdvancedOption = 'ungroupGreens' | 'minified' | 'flatGreens';

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
];

/**
 * Thin strip above the board: three menus whose options unfold to the right
 * inside the strip itself. Dimmed options are hidden from the board.
 */
@Component({
  selector: 'app-dashboard-ribbon',
  imports: [DragScrollDirective],
  templateUrl: './dashboard-ribbon.component.html',
  styleUrl: './dashboard-ribbon.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardRibbonComponent {
  protected readonly localization = inject(LocalizationService);
  readonly settings = model.required<DashboardViewSettings>();
  readonly tags = input.required<readonly AlertTag[]>();
  protected readonly openMenu = signal<RibbonMenu | null>(null);
  protected readonly states = CARD_STATE_ORDER;
  protected readonly advancedOptions = ADVANCED_OPTIONS;
  protected readonly visibleStateCount = computed(() => this.states.length - this.settings().hiddenStates.length);
  protected readonly visibleTagCount = computed(() => {
    const hidden = new Set(this.settings().hiddenTagIds);
    return this.tags().filter((tag) => !hidden.has(tag.id)).length;
  });
  protected readonly activeAdvancedCount = computed(() =>
    this.advancedOptions.filter((option) => this.settings()[option.key]).length);

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
}
