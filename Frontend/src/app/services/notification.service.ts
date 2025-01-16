import { Injectable } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { Observable, Subject, Subscription } from 'rxjs';
import { LoggerService } from './logger.service';
import { Status } from '../data/status';
import { NotificationDto } from '../data/notification.dto';

@Injectable({
  providedIn: 'root'
})
export class NotificationService {

  private notiSupport: Boolean | null = null;
  private notiAccept: Boolean | null = null;

  private appName!: string;

  private notificationSubject: Subject<NotificationDto> = new Subject<NotificationDto>();
  private notification$: Observable<NotificationDto> = this.notificationSubject.asObservable();
  private notificationSubscription!: Subscription;

  private openNotifications!: Map<String, NotificationDto[]>;

  constructor(private titleService: Title, private logger: LoggerService) {
    this.notificationSubscription = this.notification$.subscribe(this.handleNotification.bind(this));
    this.openNotifications = new Map<String, NotificationDto[]>();
  }

  sendNotification(notificationDto: NotificationDto): void {
    this.notificationSubject.next(notificationDto);
  }

  configureAppName(name: string): void {
    this.appName = name;
  }

  private calculateTitle(): void {
    const count: number = this.countOpenNotifications();

    let candidate: string;
    
    if(count == 0) {
      candidate = this.appName;
    } else {
      candidate = `(${count}) ${this.appName}`;
    }

    if(this.titleService.getTitle() != candidate) {
      this.titleService.setTitle(candidate);
    }
  }

  private countOpenNotifications(): number {
    return Array.from(this.openNotifications.values()).reduce((sum, value) => sum + (value == null ? 0 : value.length), 0);
  }

  private registerOpenNotification(notificationDto: NotificationDto) {
    const name: string = notificationDto.name;

    const data: NotificationDto[] | undefined = this.openNotifications.get(name);

    if(data) {
      data.push(notificationDto)
    } else {
      const newArray: NotificationDto[] = [notificationDto];
      this.openNotifications.set(name, newArray);
    }
  
    this.calculateTitle();
  }

  private async requestPermission(): Promise<void> {
    if(this.notiSupport == null) {
      if ('Notification' in window) {
        this.notiSupport = true;

        const permission = await Notification.requestPermission();

        if (permission === 'granted') {
          this.notiAccept = true;
          this.logger.log('Permiso concedido para notificaciones');
        } else {
          this.notiAccept = false;
          this.logger.log('Permiso denegado para notificaciones');
        }
      } else {
        this.notiSupport = false;
        this.logger.error('Este navegador no soporta notificaciones.');
      }
    }
  }

  //TODO: Conseguir iconos para WAR y ERROR
  private getIcon(status: Status): string {
    switch(status) {
      case Status.OK:
      case Status.NA:
        return "favicon.ico";
      case Status.WARN:
        return "favicon.ico";
      case Status.ERROR:
        return "favicon.ico";
    }
  }

  private async handleNotification(notificationDto: NotificationDto): Promise<void> {
    this.logger.log('Nueva notificación recibida:', notificationDto.name);

    const requestPermission: Promise<void> = this.requestPermission();

    const icon: string = this.getIcon(notificationDto.status);

    this.registerOpenNotification(notificationDto);

    await requestPermission;
  
    if (this.notiSupport && this.notiAccept) {
      const notification = new Notification(notificationDto.name, {
        body: notificationDto.text,
        icon: icon,
        badge: icon,
        //tag: undefined,
        lang: "es-ES",
        dir: "ltr",
      });
  
      notification.onclick = () => {
        this.logger.log('Notificación clickeada');
        this.desregistrar(notificationDto);
        window.focus();
      };
  
      notification.onshow = () => {
        this.logger.log('Notificación mostrada');
      };
  
      notification.onclose = () => {
        this.logger.log('Notificación cerrada');
        this.desregistrar(notificationDto);
      };
  
      notification.onerror = (error) => {
        this.logger.error('Error en la notificación:', error);
        this.desregistrar(notificationDto);
      };
    }
  }

  private desregistrar(notificationDto: NotificationDto) : void {
    const name: string = notificationDto.name;

    this.logger.info("desregistrar ", name, notificationDto);

    const data: NotificationDto[] | undefined = this.openNotifications.get(name);

    if(data) {
      const updatedData = data.filter(item => item !== notificationDto);
      this.openNotifications.set(name, updatedData);
    }

    this.calculateTitle();
  }
}
