import { ChangeDetectionStrategy, Component } from '@angular/core';

import { EmptyTableComponent } from '../../shared/empty-table/empty-table.component';

@Component({
  selector: 'app-logs',
  imports: [EmptyTableComponent],
  template: `
    <app-empty-table
      title="Logs"
      description="Inspect application and monitoring activity."
      emptyMessage="No logs available yet"
      [columns]="columns"
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogsComponent {
  protected readonly columns = ['Timestamp', 'Level', 'Source', 'Message'];
}
