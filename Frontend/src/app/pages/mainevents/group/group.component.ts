import { Component, Input } from '@angular/core';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { FrontAlert, FrontGroupWithAlerts } from '../../../data/front.dto';
import { LoggerService } from '../../../services/logger.service';
import { HumanDatePipe } from '../../../pipes/human-date.pipe';
import { TimesAgoPipe } from '../../../pipes/times-ago.pipe';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipeText, HumanDatePipe, TimesAgoPipe],
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

  get hasAlerts(): boolean {
    return (this.group?.alerts?.length ?? 0) > 0;
  }

  get alerts(): FrontAlert[] {
    return this.group?.alerts ?? [];
  }
}
