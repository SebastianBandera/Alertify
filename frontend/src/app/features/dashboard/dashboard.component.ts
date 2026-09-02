import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';

@Component({
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent {
  protected readonly localization = inject(LocalizationService);
}
