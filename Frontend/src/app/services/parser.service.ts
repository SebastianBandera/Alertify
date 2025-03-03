import { Injectable } from '@angular/core';
import { Alert, AlertResult, GroupWithAlerts } from '../data/service.dto';
import { FrontAlert, FrontGroupWithAlerts } from '../data/front.dto';
import { LogicService } from './logic.service';
import { LoggerService } from './logger.service';
import { Status } from '../data/status.enum';

@Injectable({
  providedIn: 'root'
})
export class ParserService {

  constructor(
    private log: LoggerService
  ) {

  }

  public stringToDate(str: string): Date {
    return new Date(str);
  }

  public isValidDate(date: Date): boolean {
    return !isNaN(date.getTime());
  }

  public parseGroupWithAlerts(group: GroupWithAlerts): FrontGroupWithAlerts {
    const groupFront: FrontGroupWithAlerts = {
      name: group.name,
      group_with_alerts: group,
      alerts: this.parseAlerts(group.alerts)
    };

    return groupFront;
  }

  public parseAlerts(alerts: Alert[]): FrontAlert[] {
    return alerts.map(a => this.parseAlert(a));
  }

  public parseAlert(alert: Alert): FrontAlert {
    const alertFront: FrontAlert = {
      alert: alert,
      open: false,
      checks: [],
      status: Status.NA
    };

    /*this.logicService.getLastSucess(alert.id).subscribe({
      next: (item) => {
        if(item) {
          alertFront.last_success = item;
        }
      },
      error: (e) => this.log.error("parseAlert->getLastSucess", e)
    });

    this.logicService.getLastError(alert.id).subscribe({
      next: (item) => {
        if(item) {
          alertFront.last_error = item;
        }
      },
      error: (e) => this.log.error("parseAlert->getLastError", e)
    });*/

    /*this.logicService.processAlertResult(alert.id, alertFront.checks).subscribe({
      complete: () => {}
    })*/


    return alertFront;
  }
}
