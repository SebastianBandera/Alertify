import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { FrontAlert, FrontGroupWithAlerts } from '../../data/front.dto';
import { LoggerService } from '../logger.service';

export interface FrontAlertGroupEvent {
  alert?: FrontAlert;
  group?: FrontGroupWithAlerts;
}

@Injectable({
  providedIn: 'root'
})
export class ToggleResultsService {
  private toggleSubject:Subject<FrontAlertGroupEvent> = new Subject<FrontAlertGroupEvent>();
  private toggle$:Observable<FrontAlertGroupEvent> = this.toggleSubject.asObservable();

  
  private toggleSubjectErrors:Subject<FrontAlertGroupEvent> = new Subject<FrontAlertGroupEvent>();
  private toggleErrors$:Observable<FrontAlertGroupEvent> = this.toggleSubjectErrors.asObservable();

  constructor(private log: LoggerService) {

  }

  getTogglePrincipal$(): Observable<FrontAlertGroupEvent> {
    return this.toggle$;
  }

  emitTogglePrincipal(value: FrontAlertGroupEvent): void {
    this.log.debug("emitToggle", value);
    this.toggleSubject.next(value);
  }
}
