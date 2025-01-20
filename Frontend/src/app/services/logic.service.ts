import { Injectable } from '@angular/core';
import { LoggerService } from './logger.service';
import { BackendService } from './backend.service';
import { Alert, AlertExtradata, AlertResult, ApiPagedResponse, DateResponse, Group, GroupWithAlerts } from '../data/basic.dto';
import { Observable, Subscriber } from 'rxjs';
import { Task, TaskType } from '../data/task';
import { IndexedData } from '../data/index.data.dto';

@Injectable({
  providedIn: 'root'
})
export class LogicService {

  private alerts: IndexedData<number, Alert>;
  private groups: IndexedData<string, GroupWithAlerts>;
  private nogroups: IndexedData<number, Alert>;
  private alertsResults: IndexedData<number, AlertResult>;
  private alertsExtradata: IndexedData<number, AlertExtradata>;

  items!: GroupWithAlerts[];

  constructor(private log: LoggerService, private bckService: BackendService) {
    this.alerts = new IndexedData<number, Alert>();
    this.groups = new IndexedData<string, GroupWithAlerts>();
    this.nogroups = new IndexedData<number, Alert>();
    this.alertsResults = new IndexedData<number, AlertResult>();
    this.alertsExtradata = new IndexedData<number, AlertExtradata>();
  }

  public get(): GroupWithAlerts[] {
    return this.items;
  }

  public getLoadedAlerts(): IndexedData<number, Alert> {
    return this.alerts;
  }

  public getLoadedGroups(): IndexedData<string, GroupWithAlerts> {
    return this.groups;
  }

  public getLoadedNoGroups(): IndexedData<number, Alert> {
    return this.nogroups;
  }

  public getLoadedAlertsResults(): IndexedData<number, AlertResult> {
    return this.alertsResults;
  }

  public getLoadedAlertsResultsExtradata(): IndexedData<number, AlertExtradata> {
    return this.alertsExtradata;
  }

  public principalSearch(): Observable<Task> {
    return new Observable<Task>((observer) => {
      (async () => {
        while (true) {
          await this.syncProcess(observer);
          
          await this.wait(60000);
        }
      })();
    });
  }

  private wait(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  private async syncProcess(observer: Subscriber<Task>): Promise<void> {
    await this.syncProcessAllGroups(observer);

    await this.syncProcessAllNoGroups(observer);

    await this.syncProcessAllAlertResults(observer);
  }

  private setIndexed<K, V>(item: V, keyGetter: (arg: V) => K, indexedData: IndexedData<K, V>): void {
    if (item != null && keyGetter != null && indexedData != null) {
      const id: K = keyGetter(item);
      if (!indexedData.index.has(id)) {
        indexedData.data.push(item);
      }

      indexedData.index.set(id, item);
    }
  }

  private upsertItem<T extends { id: number }>(array: T[], newItem: T): void {
    const index = array.findIndex(item => item.id === newItem.id);

    if (index !== -1) {
      array[index] = newItem;
    } else {
      array.push(newItem);
    }
  }

  private async syncProcessAllGroups(observer: Subscriber<Task>): Promise<void> {
    let { promise: promise, resolve: resolve } = this.createControlledPromise<string>();

    this.bckService.getAllGroups().subscribe({
      next: (value: ApiPagedResponse<Group>) => {
        const groupsPart: Group[] = value.page.content;

        groupsPart.forEach(group => {
          this.setIndexed<number, Alert>(group.alert, a => a.id, this.alerts);
          const idGroup: string = group.name;
          if (this.groups.index.has(idGroup)) {
            const groupList: GroupWithAlerts | undefined = this.groups.index.get(idGroup);
            this.upsertItem(groupList?.alerts!, group.alert);
          } else {
            this.groups.index.set(idGroup, { name: group.name, alerts: [group.alert] });
          }
        });

        observer.next({
          type: TaskType.GROUPS,
          msg: "page groups",
          data: groupsPart
        })
      },
      error: this.log.error,
      complete: () => resolve('getAllGroups complete')
    });

    await promise;
  }

  private async syncProcessAllNoGroups(observer: Subscriber<Task>): Promise<void> {
    let { promise: promise, resolve: resolve } = this.createControlledPromise<string>();

    this.bckService.getAllNoGroups().subscribe({
      next: (value: ApiPagedResponse<Alert>) => {
        const alertPart: Alert[] = value.page.content;

        alertPart.forEach(item => {
          this.setIndexed<number, Alert>(item, a => a.id, this.alerts);
          this.setIndexed<number, Alert>(item, a => a.id, this.nogroups);
        });

        observer.next({
          type: TaskType.NO_GROUPS,
          msg: "page alert no groups",
          data: alertPart
        })
      },
      error: this.log.error,
      complete: () => resolve('getAllNoGroups complete')
    });

    await promise;
  }

  private async syncProcessAllAlertResults(observer: Subscriber<Task>): Promise<void> {
    const promises: Promise<string>[] = [];

    const keysAlerts: MapIterator<number> = this.alerts.index.keys();
    let result = keysAlerts.next();
    while (!result.done) {
      const key: number = result.value;

      this.syncProcessAllAlertResultExtradataByAlertId(key, promises, observer);

      this.syncProcessAllAlertResultByAlertId(key, promises, observer);

      result = keysAlerts.next();
    }

    try {
      await Promise.all(promises);
    } catch (error) {
      this.log.error("Error syncProcessAllAlertResults", error);
    }
  }

  private syncProcessAllAlertResultExtradataByAlertId(key: number, promises: Promise<string>[], observer: Subscriber<Task>): void {
    let { promise: promise, resolve: resolve } = this.createControlledPromise<string>();

    promises.push(promise);

    this.bckService.getLastSuccess(key).subscribe({
      next: (value: DateResponse) => {
        const alertResultDateResponse: DateResponse = value;

        const alertExtradata: AlertExtradata = {alertId: key, lastSucess: alertResultDateResponse}

        this.setIndexed(alertExtradata, a => a.alertId, this.alertsExtradata);

        observer.next({
          type: TaskType.ALERT_RESULTSEXTRA_DATA,
          msg: "page alert results extra data",
          data: alertExtradata
        })
      },
      error: this.log.error,
      complete: () => resolve('getLastSuccess complete')
    });
  }

  private syncProcessAllAlertResultByAlertId(key: number, promises: Promise<string>[], observer: Subscriber<Task>): void {
    let { promise: promise, resolve: resolve } = this.createControlledPromise<string>();

    promises.push(promise);

    this.bckService.getAllAlertResultByAlertId(key).subscribe({
      next: (value: ApiPagedResponse<AlertResult>) => {
        const alertResultPart: AlertResult[] = value.page.content;

        alertResultPart.forEach(item => {
          this.setIndexed(item, ar => ar.id, this.alertsResults);
        });

        observer.next({
          type: TaskType.ALERT_RESULTS,
          msg: "page alert results",
          data: alertResultPart
        })
      },
      error: this.log.error,
      complete: () => resolve('getAllAlertResultByAlertId complete')
    });
  }

  private createControlledPromise<T>() {
    let resolve: (value: T) => void;

    const promise = new Promise<T>((res) => {
      resolve = res
    });

    return { promise, resolve: resolve! };
  }
}
