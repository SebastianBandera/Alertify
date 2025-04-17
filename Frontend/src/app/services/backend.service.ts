import { Injectable } from '@angular/core';
import { firstValueFrom, Observable, of } from 'rxjs';
import { CheckGroup } from '../data/check-group';
import { Check } from '../data/check';
import { Status } from '../data/status.enum';
import { ErrorCheck } from '../data/error-check';
import { Period } from '../data/period.enum';
import { ApiService } from './api.service';
import { Alert, AlertResult, ApiPagedResponse, DateResponse, Group, PagedResponse } from '../data/service.dto';
import { PageService } from './page.service';
import { HttpClient } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class BackendService extends ApiService {

  constructor(private httpClient: HttpClient, private pageService: PageService) {
    super(httpClient);
  }

  getAlerts(page: number): Observable<ApiPagedResponse<Alert>> {
    return super.getData<ApiPagedResponse<Alert>>("alerts", { page: page });
  }

  getGroups(page: number): Observable<ApiPagedResponse<Group>> {
    return super.getData<ApiPagedResponse<Group>>("alerts/groups", { page: page });
  }

  getNoGroups(page: number): Observable<ApiPagedResponse<Alert>> {
    return super.getData<ApiPagedResponse<Alert>>("alerts/nogroups", { page: page });
  }

  getAlertResult(page: number, alertId: number): Observable<ApiPagedResponse<AlertResult>> {
    return super.getData<ApiPagedResponse<AlertResult>>("alerts/results", { page: page, needsReview: "true", 'alert.id': alertId });
  }

  getAllAlerts(): Observable<ApiPagedResponse<Alert>> {
    return this.pageService.getAllPages<Alert>(this.getAlerts.bind(this));
  }

  getAllGroups(): Observable<ApiPagedResponse<Group>> {
    return this.pageService.getAllPages<Group>(this.getGroups.bind(this));
  }

  getAllNoGroups(): Observable<ApiPagedResponse<Alert>> {
    return this.pageService.getAllPages<Alert>(this.getNoGroups.bind(this));
  }

  getAllAlertResultByAlertId(alertId: number): Observable<ApiPagedResponse<AlertResult>> {
    return this.pageService.getAllPages<AlertResult>((page: number) =>
      this.getAlertResult(page, alertId)
    );
  }

  getLastSuccess(alertId: number): Observable<DateResponse> {
    return super.getData<DateResponse>("alerts/results/lastSuccess", {alertId: alertId});
  }

  getLastIssue(alertId: number): Observable<DateResponse> {
    return super.getData<DateResponse>("alerts/results/lastIssue", {alertId: alertId});
  }
  
  resolve(idAlertResult: number): Observable<void> {
    return super.postData<void>("alerts/results/" + idAlertResult + "/resolve", null);
  }
}
