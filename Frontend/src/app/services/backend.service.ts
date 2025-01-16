import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { CheckGroup } from '../data/check-group';
import { Check } from '../data/check';
import { Status } from '../data/status';
import { ErrorCheck } from '../data/error-check';
import { Period } from '../data/period';
import { ApiService } from './api.service';
import { Alert, Group, PagedResponse } from '../data/basic.dto';

@Injectable({
  providedIn: 'root'
})
export class BackendService extends ApiService {

  getInfo(): Observable<CheckGroup[]> {
    const chk1:Check = new Check('Check1', Status.OK, new Date('2024-08-25T15:00:00'), Period.DAILY, []);
    const chk2:Check = new Check('Check2', Status.WARN, new Date('2024-08-25T15:01:00'), Period.DAILY, [new ErrorCheck(new Date(), 'Error1', Status.WARN)]);
    const chk3:Check = new Check('Check3', Status.ERROR, new Date('2024-08-25T15:03:00'), Period.DAILY, [new ErrorCheck(new Date(), 'Error2', Status.ERROR), new ErrorCheck(new Date(), 'Error3', Status.ERROR)]);

    const cg1:CheckGroup = new CheckGroup('Group1', [chk1, chk2, chk3]);

    const chk12:Check = new Check('Check1b', Status.WARN, new Date('2024-08-25T17:00:00'), Period.DAILY, [new ErrorCheck(new Date(), 'Error4', Status.WARN)]);
    const chk22:Check = new Check('Check2b', Status.OK, new Date('2024-08-25T17:01:00'), Period.DAILY, []);
    const chk32:Check = new Check('Check3b', Status.ERROR, new Date('2024-08-25T17:03:00'), Period.DAILY, [new ErrorCheck(new Date(), 'Error5', Status.ERROR)]);

    const cg2:CheckGroup = new CheckGroup('Group2', [chk12, chk22, chk32]);

    const data:CheckGroup[] = [cg1, cg2];

    return of(data);
  }

  getGroups(page: number): Observable<PagedResponse<Group>> {
    return super.getData("alerts/groups", { page: page });
  }

  getNoGroups(page: number): Observable<PagedResponse<Alert>> {
    return super.getData("alerts/nogroups", { page: page });
  }
}
