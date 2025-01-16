import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SpinnerComponent } from './comp/spinner/spinner.component';
import { NotificationService } from './services/notification.service';
import { environment } from '../environments/environment';

@Component({
    selector: 'app-root',
    imports: [RouterOutlet, SpinnerComponent],
    templateUrl: './app.component.html',
    styleUrl: './app.component.css'
})
export class AppComponent {
  title = environment.appName;

  constructor(private notificationService: NotificationService) {}

  async ngOnInit(): Promise<void> {
    this.notificationService.configureAppName(this.title);
  }
}
