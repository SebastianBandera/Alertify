import { Injectable } from '@angular/core';
import { LoggerService } from './logger.service';
import { BackendService } from './backend.service';
import { Alert, AlertResult, ApiPagedResponse, Group, GroupWithAlerts, StatusResult } from '../data/service.dto';
import { map, Observable} from 'rxjs';
import { IndexedData } from '../data/index.data.dto';
import { FrontAlert, FrontGroupWithAlerts, FrontResult } from '../data/front.dto';
import { ParserService } from './parser.service';
import { Status } from '../data/status.enum';
import { CollectionUtils } from '../utils/collection.utils';
import { GeneralUtils } from '../utils/general.utils';
import { MessageService } from './message.service';

@Injectable({
  providedIn: 'root'
})
export class LogicService {

  private alerts: IndexedData<number, Alert>;
  /*private groups: IndexedData<string, GroupWithAlerts>;
  private nogroups: IndexedData<number, Alert>;
  private alertsResults: IndexedData<number, AlertResult>;
  private alertsResultsByAlert: IndexedData<number, AlertResult[]>;
  private alertsExtradata: IndexedData<number, AlertExtradata>;*/

  private alertsResults: IndexedData<number, AlertResult>;

  //FrontEnd Data
  private groupsFront: IndexedData<string, FrontGroupWithAlerts>;
  private noGroupsFront: IndexedData<number, FrontAlert>;

  

  private collectionUtils: CollectionUtils = new CollectionUtils();
  private generalUtils: GeneralUtils = new GeneralUtils();

  constructor(private log: LoggerService, private bckService: BackendService, private parserService: ParserService, private messageService: MessageService) {
    this.alerts = new IndexedData<number, Alert>();
    /*this.groups = new IndexedData<string, GroupWithAlerts>();
    this.nogroups = new IndexedData<number, Alert>();
    this.alertsResults = new IndexedData<number, AlertResult>();
    this.alertsResultsByAlert = new IndexedData<number, AlertResult[]>();
    this.alertsExtradata = new IndexedData<number, AlertExtradata>();*/

    this.alertsResults = new IndexedData<number, AlertResult>();

    this.groupsFront = new IndexedData<string, FrontGroupWithAlerts>();
    this.noGroupsFront = new IndexedData<number, FrontAlert>();
  }

  public getGroupsFront(): IndexedData<string, FrontGroupWithAlerts> {
    return this.groupsFront;
  }

  public getNoGroupsFront(): IndexedData<number, FrontAlert> {
    return this.noGroupsFront;
  }

  public async syncProcess(): Promise<void> {
    const currentAlerts = new Map<number, FrontAlert>();

    this.log.debug("syncProcess init");

    this.syncProcessAllGroups(currentAlerts);
    //No await
    this.syncProcessAllNoGroups(currentAlerts);
  }

  private async syncProcessAllGroups(currentAlerts: Map<number, FrontAlert>): Promise<void> {
    this.bckService.getAllGroups().subscribe({
      next: (value: ApiPagedResponse<Group>) => {
        const groupsPart: Group[] = value.page.content;

        groupsPart.forEach((group: Group) => {
          if(group == null || group.id == null) return;

          const id: string = group.name;

          const savedItem:FrontGroupWithAlerts | undefined = this.groupsFront.getIndex.get(id);


          if(savedItem == undefined) {
            //Nuevo
            const alreadyProcessedFrontAlert: FrontAlert | undefined = currentAlerts.get(group.alert.id);
            const newItem: FrontGroupWithAlerts = this.parseGroup(group);
            if(alreadyProcessedFrontAlert == undefined) {
              //Alerta no procesada en este ciclo
              const frontAlert: FrontAlert = this.parseAlert(group.alert);
              currentAlerts.set(group.alert.id, frontAlert);

              newItem.alerts.push(frontAlert);
  
              this.groupsFront.setIndexed(newItem, item => item.name);
            } else {
              //Alerta ya procesado en este ciclo, quizas desde otro grupo
              newItem.alerts.push(alreadyProcessedFrontAlert);
  
              this.groupsFront.setIndexed(newItem, item => item.name);
            }
          } else {
            //Grupo ya agregado, puede ahora agregarle más alertas

            //Actualiza originales
            this.collectionUtils.upsertItemArray(savedItem.group_with_alerts.alerts, group.alert);
            
            const alreadyProcessedFrontAlert: FrontAlert | undefined = currentAlerts.get(group.alert.id);
            if(alreadyProcessedFrontAlert == undefined) {
              //Alerta no procesada en este ciclo
              const frontAlert: FrontAlert = this.parseAlert(group.alert);
              currentAlerts.set(group.alert.id, frontAlert);
              this.collectionUtils.upsertItemKeysArrayByIdExtractor(savedItem.alerts, frontAlert, ["period", "alert"], (item: FrontAlert) => item.alert.id);
            } else {
              //Alerta ya procesado en este ciclo, quizas desde otro grupo
              this.collectionUtils.upsertItemKeysArrayByIdExtractor(savedItem.alerts, alreadyProcessedFrontAlert, ["period", "alert"], (item: FrontAlert) => item.alert.id);
            }
          }
        });
      },
      error: this.log.error,
      complete: () => []
    });
  }

  private async syncProcessAllNoGroups(currentAlerts: Map<number, FrontAlert>): Promise<void> {
    this.bckService.getAllNoGroups().subscribe({
      next: (value: ApiPagedResponse<Alert>) => {
        const alertPart: Alert[] = value.page.content;

        alertPart.forEach((alert: Alert) => {
          if(alert == null || alert.id == null) return;

          const id: number = alert.id;

          const savedItem: FrontAlert | undefined = this.noGroupsFront.getIndex.get(id);

          if(savedItem == undefined) {
            //Nuevo
            const alreadyProcessedFrontAlert: FrontAlert | undefined = currentAlerts.get(id);
            if(alreadyProcessedFrontAlert == undefined) {
              //Alerta no procesada en este ciclo
              const frontAlert: FrontAlert = this.parseAlert(alert);
              currentAlerts.set(alert.id, frontAlert);

              this.noGroupsFront.setIndexed(frontAlert, item => item.alert.id);
            } else {
              //Alerta ya procesado en este ciclo, quizas desde otro grupo
              this.noGroupsFront.setIndexed(alreadyProcessedFrontAlert, item => item.alert.id);
            }
          } else {
            //Alerta ya agregada, actualizar
            const alreadyProcessedFrontAlert: FrontAlert | undefined = currentAlerts.get(id);
            if(alreadyProcessedFrontAlert == undefined) {
              //Alerta no procesada en este ciclo
              const frontAlert: FrontAlert = this.parseAlert(alert);
              currentAlerts.set(alert.id, frontAlert);

              this.collectionUtils.upsertItemKeys(savedItem, frontAlert, ["last_success", "last_issue", "alert"]);
            } else {
              //Alerta ya procesado en este ciclo, quizas desde otro grupo
              this.collectionUtils.upsertItemKeys(savedItem, alreadyProcessedFrontAlert, ["last_success", "last_issue", "alert"]);
            }
          }
        });
      },
      error: this.log.error,
      complete: () => []
    });
  }

  private parseGroup(group: Group): FrontGroupWithAlerts {
    const groupList: GroupWithAlerts = { name: group.name, alerts: [group.alert] };

    const groupWithAlerts: FrontGroupWithAlerts = {
      name: group.name,
      alerts: [],
      group_with_alerts: groupList
    }

    return groupWithAlerts;
  }

  public getLastSuccess(idAlert: number): Observable<Date | undefined> {
    return this.bckService.getLastSuccess(idAlert).pipe(
      map((lastSuccess) => {
        if(lastSuccess) {
          const date: Date | undefined = this.parserService.stringToDate(lastSuccess.date);
          return date;
        } else {
          return undefined;
        }
      }),
    );
  }

  public getLastIssue(idAlert: number): Observable<Date | undefined> {
    return this.bckService.getLastIssue(idAlert).pipe(
      map((lastSuccess) => {
        if(lastSuccess) {
          const date: Date | undefined = this.parserService.stringToDate(lastSuccess.date);
          return date;
        } else {
          return undefined;
        }
      }),
    );
  }

  public resolve(idAlertResult: number): void {
    this.bckService.resolve(idAlertResult);
  }

  /*public getLastError(idAlert: number): Observable<Date | undefined> {
    return new Observable<Date | undefined>((observer) => {
      (async () => {
        let result: Date | undefined = undefined;

        const cachedAlertResults: AlertResult[] | undefined = this.getLoadedAlertsResultsByAlert().index.get(idAlert);

        if (cachedAlertResults) {
          const date: Date | undefined =
              cachedAlertResults
                .filter(a => a != null)
                .filter(a => a.needsReview == true)
                .filter(a => a.statusResult.name == StatusResultEnum.WARN || a.statusResult.name == StatusResultEnum.ERROR)
                .map(a => a.dateIni)
                .map(d => this.parserService.stringToDate(d))
                .filter(d => this.parserService.isValidDate(d))
                .sort((a,b) => b.getTime()-a.getTime())
                .shift();
          
          if(date) {
            result = date;
          }
        }

        observer.next(result);
        observer.complete();
      })();
    });
  }*/

  /*public processAlertResult(idAlert: number, checks: FrontChecks[]): Observable<void> {
    return new Observable<void>((observer) => {
      (async () => {
        const cachedAlertResults: AlertResult[] | undefined = this.getLoadedAlertsResultsByAlert().index.get(idAlert);
    
        if(cachedAlertResults) {
          cachedAlertResults.forEach(a => {
            
          })
        }

        observer.complete();
      })();
    });
  }*/

  /***
   * Sync and wait, repeat
   */
  /*public principalSearch(): Observable<Task> {
    return new Observable<Task>((observer) => {
      (async () => {
        while (true) {
          await this.syncProcess(observer);
          
          await this.wait(60000);

          this.log.log("Sync refresh");
        }
      })();
    });
  }*/

  private wait(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  /*private async syncProcessAllAlertResults(observer: Subscriber<Task>): Promise<void> {
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
  }*/

  /*private syncProcessAllAlertResultExtradataByAlertId(key: number, promises: Promise<string>[], observer: Subscriber<Task>): void {
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
  }*/

  /*private syncProcessAllAlertResultByAlertId(key: number, promises: Promise<string>[], observer: Subscriber<Task>): void {
    let { promise: promise, resolve: resolve } = this.createControlledPromise<string>();

    promises.push(promise);

    this.bckService.getAllAlertResultByAlertId(key).subscribe({
      next: (value: ApiPagedResponse<AlertResult>) => {
        const alertResultPart: AlertResult[] = value.page.content;

        alertResultPart.forEach(item => {
          this.setIndexed(item, ar => ar.id, this.alertsResults);
        });

        if(alertResultPart && alertResultPart.length > 0) {
          this.setIndexed(alertResultPart, ar => ar[0].alert.id ?? -1, this.alertsResultsByAlert);
        }

        observer.next({
          type: TaskType.ALERT_RESULTS,
          msg: "page alert results",
          data: alertResultPart
        })
      },
      error: this.log.error,
      complete: () => resolve('getAllAlertResultByAlertId complete')
    });
  }*/

  private createControlledPromise<T>() {
    let resolve: (value: T) => void;

    const promise = new Promise<T>((res) => {
      resolve = res
    });

    return { promise, resolve: resolve! };
  }

  public parseAlert(alert: Alert): FrontAlert {
    const alertFront: FrontAlert = {
      alert: alert,
      open: false,
      open_errors: true,
      period: this.generalUtils.tryableSupplier(()=>this.generalUtils.parseIsoDuration(alert.periodicity), this.log.error, ()=>""),
      results: [],
      status: Status.NA
    };

    this.getLastSuccess(alert.id).subscribe({
      next: (item) => {
        if(item) {
          alertFront.last_success = item;
        }
      },
      error: (e) => this.log.error("parseAlert->getLastSuccess", e)
    });

    this.getLastIssue(alert.id).subscribe({
      next: (item) => {
        if(item) {
          alertFront.last_issue = item;
        }
      },
      error: (e) => this.log.error("parseAlert->getLastIssue", e)
    });

    this.bckService.getAllAlertResultByAlertId(alert.id).subscribe({
      next: (value: ApiPagedResponse<AlertResult>) => {
        const alertResults: AlertResult[] = value.page.content;

        let resultPushed: boolean = false;

        alertResults.forEach((alertResult: AlertResult) => {
          if(alertResult == null || alertResult.id == null) return;

          const id: number = alertResult.id;

          const savedItem: AlertResult | undefined = this.alertsResults.getIndex.get(id);

          if(savedItem == undefined) {
            //Nuevo
            const frontResult: FrontResult = this.parseAlertResult(alertResult);

            this.alertsResults.setIndexed(alertResult, item => item.id);
            alertFront.results.push(frontResult);
            resultPushed = true;
          } else {
            //Resultado de alerta ya agregado, inmutable, no actualizar
          }
        });

        if(resultPushed) {
          if(alertFront.results.find(v => v.status == Status.ERROR)) {
            alertFront.status = Status.ERROR;
          } else if (alertFront.results.find(v => v.status == Status.WARN)) {
            alertFront.status = Status.WARN;
          } else {
            alertFront.status = Status.OK;
          }
        }
      },
      error: this.log.error,
      complete: () => []
    });

    /*this.getLastError(alert.id).subscribe({
      next: (item) => {
        if(item) {
          alertFront.last_error = item;
        }
      },
      error: (e) => this.log.error("parseAlert->getLastError", e)
    });*/

    /*this.processAlertResult(alert.id, alertFront.checks).subscribe({
      complete: () => {}
    })*/


    return alertFront;
  }

  
  public parseAlertResult(alertResult: AlertResult): FrontResult {
    //const { mensaje, descripcion } = this.messageService.process(alertResult);
    const frontResult: FrontResult = {
      alert_result: alertResult,
      status: this.parseStatusResult(alertResult.statusResult),
      time: this.processTimeMessage(alertResult),
      message: "mensaje",
      descripcion: "descripcion"
    };
    
    return frontResult;
  }

  private processTimeMessage(alertResult: AlertResult): string {
    let result: string = "";
    try {
      const dateIni: Date = new Date(alertResult.dateIni);
      const dateEnd: Date = new Date(alertResult.dateEnd);

      const timeMessage: string = this.generalUtils.formatDate(alertResult.dateIni);
      
      const minutosDiff: number = this.generalUtils.diffInMinutes(dateIni, dateEnd);
      
      if(minutosDiff == 1) {
        result = timeMessage + " (durante " + minutosDiff + " minuto)";
      } else if (minutosDiff > 1) {
        result = timeMessage + " (durante " + minutosDiff + " minutos)";
      } else {
        result = timeMessage;
      }
    } catch (error) {
      this.log.error(error);
      result = "Parse error";
    }

    return result;
  }

  private parseStatusResult(statusResult: StatusResult): Status {
    if(statusResult==null) return Status.NA;

    switch(statusResult.name) {
      case "success": return Status.OK;
      case "warn": return Status.WARN;
      case "error": return Status.ERROR;
      default: return Status.NA;
    }
  }
  
}
