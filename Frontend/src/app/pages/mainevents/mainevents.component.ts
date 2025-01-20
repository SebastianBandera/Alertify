import { Component, ViewEncapsulation } from '@angular/core';
import { BackendService } from '../../services/backend.service';
import { CheckGroup } from '../../data/check-group';
import { CommonModule } from '@angular/common';
import { Check } from '../../data/check';
import { Status } from '../../data/status';
import { NotificationService } from '../../services/notification.service';
import { LoggerService } from '../../services/logger.service';
import { NotificationDto } from '../../data/notification.dto';
import { TestBoxComponent } from '../../comp/test-box/test-box.component';
import { StatusComponent } from './status/status.component';
import { ButtonUpDownComponent } from './button-up-down/button-up-down.component';
import { GroupComponent } from './group/group.component';
import { LogicService } from '../../services/logic.service';
import { Task, TaskType } from '../../data/task';
import { Alert, GroupWithAlerts } from '../../data/basic.dto';

@Component({
  selector: 'app-mainevents',
  imports: [CommonModule, TestBoxComponent, GroupComponent],
  templateUrl: './mainevents.component.html',
  styleUrls: ['./mainevents.component.css', './c1.css', './c2.css', './main.css'],
  encapsulation: ViewEncapsulation.None
})
export class MaineventsComponent {

  groups: GroupWithAlerts[] = [];

  constructor(private backend: BackendService,
    private logicService: LogicService,
    private notification: NotificationService,
    private logger: LoggerService) {
  }

  ngOnInit(): void {
    this.logicService.principalSearch().subscribe({
      next: (value: Task) => {
        this.logger.log(value);
        if (value != null) {
          switch (value.type) {
            case TaskType.ALERTS:

              break;
            case TaskType.ALERT_RESULTS:

              break;
            case TaskType.ALERT_RESULTSEXTRA_DATA:

              break;
            case TaskType.GROUPS:
              this.groups = this.logicService.getLoadedGroups().data;
              break;
            case TaskType.NO_GROUPS:

              break;
            default:
              break;
          }
        }
      },
      error: this.logger.error,
      complete: () => this.logger.log("Load complete!")
    })
  }

  onButtonClick(): void {
    this.logger.log('El botón ha sido clickeado');

    //this.notification.sendNotification(new NotificationDto("name", "text", Status.WARN));
  }
}