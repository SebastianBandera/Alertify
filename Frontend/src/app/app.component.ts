import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SpinnerComponent } from './comp/spinner/spinner.component';
import { NotificationService } from './services/notification.service';
import { environment } from '../environments/environment';
import { BackendService } from './services/backend.service';
import { LogicService } from './services/logic.service';
import { LoggerService } from './services/logger.service';

@Component({
    selector: 'app-root',
    imports: [RouterOutlet, SpinnerComponent],
    templateUrl: './app.component.html',
    styleUrl: './app.component.css'
})
export class AppComponent {
  title = environment.appName;

  constructor(
    private log: LoggerService,
    private notificationService: NotificationService, 
    private backendService: BackendService,
    private logicService: LogicService) {}

  async ngOnInit(): Promise<void> {
    this.notificationService.configureAppName(this.title);
  }
}
