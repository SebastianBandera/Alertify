import { CommonModule } from '@angular/common';
import { Component, Input, TemplateRef, ViewChild } from '@angular/core';
import { FrontAlert, FrontGroupWithAlerts } from '../../../data/front.dto';
import { LoggerService } from '../../../services/logger.service';
import { ToggleResultsService } from '../../../services/events/toggle-results.service';

@Component({
  selector: 'app-button-up-down',
  imports: [CommonModule],
  templateUrl: './button-up-down.component.html',
  styleUrl: './button-up-down.component.css'
})
export class ButtonUpDownComponent {
  private status_up: boolean | null = null;

  @Input() alert?: FrontAlert;
  @Input() group?: FrontGroupWithAlerts;

  @ViewChild('boton_down', { static: true }) boton_down!: TemplateRef<any>;
  @ViewChild('boton_up', { static: true }) boton_up!: TemplateRef<any>;
  @ViewChild('boton_null', { static: true }) boton_null!: TemplateRef<any>;
  
  constructor(private eventService: ToggleResultsService, private logger: LoggerService) {

  }

  ngOnInit(): void {
    this.logger.debug('ButtonUpDownComponent ngOnInit ' + this.alert?.alert.name);
    if(this.alert?.open == null) {
      this.status_up = null;
    } else {
      this.status_up = !this.alert?.open;
    }
  }

  ngOnDestroy(): void {
    this.logger.debug('ButtonUpDownComponent ngOnDestroy ' + this.alert?.alert.name)
  }

    getTemplate(): TemplateRef<any> | null {
      switch (this.status_up) {
        case null:
          return this.boton_null;
        case true:
          return this.boton_up;
        case false:
          return this.boton_down;
        default:
          return null;
      }
    }

    toggleStatus(): void {
      this.status_up = !this.status_up;
      this.eventService.emitTogglePrincipal({alert: this.alert, group: this.group});
    }
}
