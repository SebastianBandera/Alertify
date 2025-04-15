import { Component, Input } from '@angular/core';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { FrontAlert, FrontGroupWithAlerts } from '../../../data/front.dto';
import { LoggerService } from '../../../services/logger.service';
import { HumanDatePipe } from '../../../pipes/human-date.pipe';
import { TimesAgoPipe } from '../../../pipes/times-ago.pipe';
import { ArrayLenPipe } from '../../../pipes/array-len.pipe';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipeText, HumanDatePipe, TimesAgoPipe, ArrayLenPipe],
  templateUrl: './group.component.html',
  styleUrl: './group.component.css'
})
export class GroupComponent {
  @Input() group?: FrontGroupWithAlerts;

  private openedAlerts: Set<number>;

  constructor(private logger: LoggerService) {
    this.openedAlerts = new Set<number>();
  }

  testChanges(): void {
     
  }

  ngOnInit(): void {
    this.logger.debug('GroupComponent ngOnInit ' + this.group?.name, this.group)
  }

  ngOnDestroy(): void {
    this.logger.debug('GroupComponent ngOnDestroy ' + this.group?.name)
  }

  togglePrincipal(alert: FrontAlert): void {
    alert.open = !alert.open;
  }

  toggleErrorButton(alert: FrontAlert): void {
    alert.open_errors = !alert.open_errors;
  }

  processIssueMessage(alert: FrontAlert): string {
    const errors: number = alert.results.filter(r => r.status != 1).length;

    if(errors == 0) return "Ok";
    if(errors == 1) return "1 active error";
    return errors + " active errors";
  }

  get hasAlerts(): boolean {
    return (this.group?.alerts?.length ?? 0) > 0;
  }

  get alerts(): FrontAlert[] {
    return this.group?.alerts ?? [];
  }
}
