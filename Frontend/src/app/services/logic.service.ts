import { Injectable } from '@angular/core';
import { LoggerService } from './logger.service';
import { BackendService } from './backend.service';
import { Alert, ApiPagedResponse, Group, GroupList } from '../data/basic.dto';
import { Observable } from 'rxjs';
import { Task, TaskType } from '../data/task';

@Injectable({
  providedIn: 'root'
})
export class LogicService {

  private alerts: Map<number, Alert>;
  private groups: Map<string, GroupList>;

  constructor(private log: LoggerService, private bckService: BackendService) {
    this.alerts = new Map<number, Alert>();
    this.groups = new Map<string, GroupList>();
  }

  private setInMap<T>(item: T, keyGetter: (arg: T) => number, map: Map<number, T>): void {
    if(item != null && keyGetter != null && map  != null) {
      const id: number = keyGetter(item);
      map.set(id, item);
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

  public principalSearch(): Observable<Task> {
    return new Observable<Task>((observer) => {
      (async () => {
        let { promise: promise1, resolve: resolve1 } = this.createControlledPromise<string>();

        this.bckService.getAllGroups().subscribe({
          next: (value: ApiPagedResponse<Group>) => { 
            const groupsPart: Group[] = value.page.content;

            groupsPart.forEach(group => {
              this.setInMap<Alert>(group.alert, a => a.id, this.alerts);
              const idGroup: string = group.name;
              if(this.groups.has(idGroup)) {
                const groupList: GroupList | undefined = this.groups.get(idGroup);
                this.upsertItem(groupList?.alerts!, group.alert);
              } else {
                this.groups.set(idGroup, {name: group.name, alerts: [group.alert]});
              }
            });
            
            observer.next({
              type: TaskType.GROUPS,
              msg: "page groups",
              data: groupsPart
            })
          },
          error: this.log.error,
          complete: () => resolve1('getAllGroups complete')
        });

        await promise1;

        let { promise: promise2, resolve: resolve2 } = this.createControlledPromise<string>();

        this.bckService.getAllNoGroups().subscribe({
          next: (value: ApiPagedResponse<Alert>) => { 
            const alertPart: Alert[] = value.page.content;

            alertPart.forEach(item => {
              this.setInMap<Alert>(item, a => a.id, this.alerts);
            });

            observer.next({
              type: TaskType.NO_GROUPS,
              msg: "page alert no groups",
              data: alertPart
            })
          },
          error: this.log.error,
          complete: () => resolve2('getAllNoGroups complete')
        });
        
        await promise2;

        observer.complete();
      })();
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
