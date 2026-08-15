import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-empty-table',
  templateUrl: './empty-table.component.html',
  styleUrl: './empty-table.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyTableComponent {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
  readonly emptyMessage = input.required<string>();
  readonly columns = input.required<readonly string[]>();
}
