import { Component, ViewEncapsulation } from '@angular/core';
import { BackendService } from '../../services/backend.service';
import { CheckGroup } from '../../data/check-group';
import { CommonModule } from '@angular/common';
import { Check } from '../../data/check';
import { Status } from '../../data/status';
import { NotificationService } from '../../services/notification.service';
import { LoggerService } from '../../services/logger.service';
import { NotificationDto } from '../../data/notification.dto';

@Component({
    selector: 'app-mainevents',
    imports: [CommonModule],
    templateUrl: './mainevents.component.html',
    styleUrls: ['./mainevents.component.css', './c1.css', './c2.css', './main.css'],
    encapsulation: ViewEncapsulation.None
})
export class MaineventsComponent {
  data: CheckGroup[] = [];

  Status = Status;

  test: Check[] = [];

  constructor(private backend:BackendService, private notification:NotificationService, private logger: LoggerService) {

  }

  ngOnInit(): void {
    this.backend.getInfo().subscribe({
      next: (response) => {
        this.data = response;
        this.logger.log(response);
        this.test = [this.data[0].checks[2]];
      },
      error: (e) => this.logger.error(e),
      complete: () => this.logger.info('Load completed')
    });
  }

  onButtonClick(): void {
    this.logger.log('El botón ha sido clickeado');

    this.notification.sendNotification(new NotificationDto("name", "text", Status.WARN));
  }
}