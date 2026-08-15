import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';
import { EmptyTableComponent } from '../../shared/empty-table/empty-table.component';

@Component({
  selector: 'app-secrets',
  imports: [EmptyTableComponent],
  template: `
    <app-empty-table
      [title]="localization.translate('secrets.title')"
      [description]="localization.translate('secrets.description')"
      [emptyMessage]="localization.translate('secrets.empty')"
      [columns]="columns()"
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecretsComponent {
  protected readonly localization = inject(LocalizationService);
  protected readonly columns = computed(() => [
    this.localization.translate('secrets.column.name'),
    this.localization.translate('secrets.column.scope'),
    this.localization.translate('secrets.column.updatedAt'),
    this.localization.translate('secrets.column.status'),
  ]);
}
