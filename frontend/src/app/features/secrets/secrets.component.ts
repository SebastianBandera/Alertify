import { ChangeDetectionStrategy, Component } from '@angular/core';

import { EmptyTableComponent } from '../../shared/empty-table/empty-table.component';

@Component({
  selector: 'app-secrets',
  imports: [EmptyTableComponent],
  template: `
    <app-empty-table
      title="Secrets"
      description="Review secret metadata without exposing stored values."
      emptyMessage="No secrets available yet"
      [columns]="columns"
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecretsComponent {
  protected readonly columns = ['Name', 'Scope', 'Updated at', 'Status'];
}
