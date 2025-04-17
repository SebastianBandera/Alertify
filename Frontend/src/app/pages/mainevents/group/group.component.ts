import { Component, Input } from '@angular/core';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { FrontAlert, FrontGroupWithAlerts, FrontResult } from '../../../data/front.dto';
import { LoggerService } from '../../../services/logger.service';
import { HumanDatePipe } from '../../../pipes/human-date.pipe';
import { TimesAgoPipe } from '../../../pipes/times-ago.pipe';
import { ArrayLenPipe } from '../../../pipes/array-len.pipe';
import { Status } from '../../../data/status.enum';

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
    if(alert.results.length == 0) return "No elements";
    const errors: number = alert.results.filter(r => r.status != 1).length;
    if(errors == 0) return "Ok";
    return errors + " active";
  }

  get hasAlerts(): boolean {
    return (this.group?.alerts?.length ?? 0) > 0;
  }

  get alerts(): FrontAlert[] {
    return this.group?.alerts ?? [];
  }

  hasAlertsResult(frontAlert: FrontAlert): boolean {
    return (frontAlert.results.length ?? 0) > 0;
  }

  alertsResults(frontAlert: FrontAlert): FrontResult[] {
    return frontAlert.results ?? [];
  }

  getFirst(frontResults: FrontResult[]): FrontResult {
    return frontResults[0];
  }

  getTimeRange(frontResults: FrontResult[]): {first: FrontResult, last: FrontResult} {
    const sortedGroup: FrontResult[] = frontResults.sort((a, b) =>
      new Date(b.alert_result.dateIni).getTime() - new Date(a.alert_result.dateIni).getTime()
    );

    return {first: sortedGroup[0], last: sortedGroup[sortedGroup.length == 0 ? 0 : sortedGroup.length - 1]}
  }

  alertsResultsGrouped(frontAlert: FrontAlert): FrontResult[][] {
    const groupedMap = new Map<string, FrontResult[]>();

    for (const result of frontAlert.results) {
      const key = result.message + '-' + result.descripcion + '-' + result.status;
      if (!groupedMap.has(key)) {
        groupedMap.set(key, []);
      }
      groupedMap.get(key)!.push(result);
    }

    const finalList: FrontResult[][] = [];

    for (const group of groupedMap.values()) {
      const sortedGroup: FrontResult[] = group.sort((a, b) =>
        new Date(b.alert_result.dateIni).getTime() - new Date(a.alert_result.dateIni).getTime()
      );
      finalList.push(sortedGroup);
    }

    return finalList;
  }

  getStatus(id: number): string {
    return Status[id];
  }
}
