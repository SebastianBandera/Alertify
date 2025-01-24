import { Component, Input, SimpleChanges } from '@angular/core';
import { Status } from '../../../data/status';
import { Check } from '../../../data/check';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { GroupWithAlerts } from '../../../data/basic.dto';
import { LogicService } from '../../../services/logic.service';
import { FrontAlert, FrontGroupWithAlerts } from '../../../data/front.dto';
import { LoggerService } from '../../../services/logger.service';
import { ParserService } from '../../../services/parser.service';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipeText],
  templateUrl: './group.component.html',
  styleUrl: './group.component.css'
})
export class GroupComponent {
  @Input() group?: GroupWithAlerts;

  data?: FrontGroupWithAlerts;

  status?: Status;

  test?: Check;

  constructor(
    private logicService: LogicService,
    private log: LoggerService,
    private parser: ParserService
  ) {
    /**
     * Tomar group.alerts para saber los ids de las alertas
     * Por cada uno, generar un objeto combinado para tener la alerta y datos extra calculados, 
     * como por ejemplo 'status' y 'last error'
     */
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['group']?.firstChange && this.group) {
      this.data = {
        name: this.group?.name ?? "",
        group_with_alerts: this.group,
        alerts: []
      }
    }

    if (changes['group']?.currentValue) {
      this.processAlerts();
    }
  }

  private processAlerts(): void {
    if(this.group && this.group.alerts) {
      this.group.alerts.forEach(alert => {
        const frontAlert: FrontAlert = {
          alert: alert,
          open: false,
        };

        this.logicService.getLastSucess(alert.id).subscribe({
          next: (item) => {
            frontAlert.last_success = this.parser.stringToDate(item.date);
          },
          error: (e) => this.log.error("processAlerts->getLastSucess", e)
        });
      });
    }
  }



  ngOnInit(): void {
    console.log(this.group);
  }

  get hasAlerts(): boolean {
    return (this.data?.alerts?.length ?? 0) > 0;
  }

  get alerts(): FrontAlert[] {
    return this.data?.alerts ?? [];
  }
}
