import { Injectable } from '@angular/core';
import { Alert, GroupWithAlerts } from '../data/basic.dto';
import { FrontAlert, FrontGroupWithAlerts } from '../data/front.dto';

@Injectable({
  providedIn: 'root'
})
export class ParserService {

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
      open: false
    };

    return alertFront;
  }
}
