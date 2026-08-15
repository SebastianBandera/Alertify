import { ChangeDetectionStrategy, Component } from '@angular/core';

import { EmptyTableComponent } from '../../shared/empty-table/empty-table.component';

@Component({
  selector: 'app-configs',
  imports: [EmptyTableComponent],
  template: `
    <app-empty-table
      title="Configs"
      description="Manage application and monitoring configuration."
      emptyMessage="No configs available yet"
      [columns]="columns"
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigsComponent {
  protected readonly columns = ['Name', 'Type', 'Updated at', 'Status'];
}
