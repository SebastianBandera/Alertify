import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';

@Component({
  selector: 'app-empty-table',
  templateUrl: './empty-table.component.html',
  styleUrl: './empty-table.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyTableComponent {
  protected readonly localization = inject(LocalizationService);
  readonly title = input.required<string>();
  readonly description = input.required<string>();
  readonly emptyMessage = input.required<string>();
  readonly columns = input.required<readonly string[]>();
}
