import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';
import { EmptyTableComponent } from '../../shared/empty-table/empty-table.component';

@Component({
  selector: 'app-logs',
  imports: [EmptyTableComponent],
  template: `
    <app-empty-table
      [title]="localization.translate('logs.title')"
      [description]="localization.translate('logs.description')"
      [emptyMessage]="localization.translate('logs.empty')"
      [columns]="columns()"
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogsComponent {
  protected readonly localization = inject(LocalizationService);
  protected readonly columns = computed(() => [
    this.localization.translate('logs.column.timestamp'),
    this.localization.translate('logs.column.level'),
    this.localization.translate('logs.column.source'),
    this.localization.translate('logs.column.message'),
  ]);
}
