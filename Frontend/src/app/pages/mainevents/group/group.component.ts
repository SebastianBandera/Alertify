import { Component, Input, SimpleChanges } from '@angular/core';
import { Status } from '../../../data/status';
import { Check } from '../../../data/check';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { Group, GroupWithAlerts } from '../../../data/basic.dto';
import { LogicService } from '../../../services/logic.service';
import { FrontAlert, FrontGroupWithAlerts } from '../../../data/front.dto';
import { LoggerService } from '../../../services/logger.service';
import { ParserService } from '../../../services/parser.service';
import { Task, TaskType } from '../../../data/task';
import { filter } from 'rxjs';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipeText],
  templateUrl: './group.component.html',
  styleUrl: './group.component.css'
})
export class GroupComponent {
  @Input() group?: FrontGroupWithAlerts;

  //data?: FrontGroupWithAlerts;

  status?: Status;

  test?: Check;

  constructor(
    private logicService: LogicService,
    private logger: LoggerService,
    private parser: ParserService
  ) {
    
  }

  testChanges(): void {
     
  }

  ngOnInit(): void {
    
  }

  get hasAlerts(): boolean {
    return (this.group?.alerts?.length ?? 0) > 0;
  }

  get alerts(): FrontAlert[] {
    return this.group?.alerts ?? [];
  }
}
