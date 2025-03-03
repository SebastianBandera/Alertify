import { Component, Input, SimpleChanges } from '@angular/core';
import { Status } from '../../../data/status.enum';
import { Check } from '../../../data/check';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { FrontAlert, FrontGroupWithAlerts } from '../../../data/front.dto';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipeText],
  templateUrl: './group.component.html',
  styleUrl: './group.component.css'
})
export class GroupComponent {
  @Input() group?: FrontGroupWithAlerts;

  constructor() {

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
