import { Component, ViewEncapsulation } from '@angular/core';
import { BackendService } from '../../services/backend.service';
import { CheckGroup } from '../../data/check-group';
import { CommonModule } from '@angular/common';
import { Check } from '../../data/check';
import { Status } from '../../data/status.enum';
import { NotificationService } from '../../services/notification.service';
import { LoggerService } from '../../services/logger.service';
import { NotificationDto } from '../../data/notification.dto';
import { TestBoxComponent } from '../../comp/test-box/test-box.component';
import { StatusComponent } from './status/status.component';
import { ButtonUpDownComponent } from './button-up-down/button-up-down.component';
import { GroupComponent } from './group/group.component';
import { LogicService } from '../../services/logic.service';
import { Task, TaskType } from '../../data/task';
import { Alert, GroupWithAlerts } from '../../data/service.dto';
import { FrontGroupWithAlerts } from '../../data/front.dto';

@Component({
  selector: 'app-mainevents',
  imports: [CommonModule, TestBoxComponent, GroupComponent],
  templateUrl: './mainevents.component.html',
  styleUrls: ['./mainevents.component.css', './c1.css', './c2.css', './main.css'],
  encapsulation: ViewEncapsulation.None
})
export class MaineventsComponent {

  groups: FrontGroupWithAlerts[] = [];

  constructor(
    private logicService: LogicService,
    private logger: LoggerService) {
  }

  ngOnInit(): void {
    this.groups = this.logicService.getLoadedGroupsFront().data;
  }

  trackByName(index: number, group: GroupWithAlerts): string {
    return group.name;
  }

  onButtonClick(): void {
    this.logger.log('El botón ha sido clickeado');

    //this.notification.sendNotification(new NotificationDto("name", "text", Status.WARN));
  }
}