import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';
import { EmptyTableComponent } from '../../shared/empty-table/empty-table.component';

@Component({
  selector: 'app-configs',
  imports: [EmptyTableComponent],
  template: `
    <app-empty-table
      [title]="localization.translate('configs.title')"
      [description]="localization.translate('configs.description')"
      [emptyMessage]="localization.translate('configs.empty')"
      [columns]="columns()"
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigsComponent {
  protected readonly localization = inject(LocalizationService);
  protected readonly columns = computed(() => [
    this.localization.translate('configs.column.name'),
    this.localization.translate('configs.column.type'),
    this.localization.translate('configs.column.updatedAt'),
    this.localization.translate('configs.column.status'),
  ]);
}
