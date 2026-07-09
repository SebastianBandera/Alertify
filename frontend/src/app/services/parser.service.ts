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
}
