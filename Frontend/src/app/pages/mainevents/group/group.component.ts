import { Component, Input } from '@angular/core';
import { CheckGroup } from '../../../data/check-group';
import { Status } from '../../../data/status';
import { Check } from '../../../data/check';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipeText } from '../../../pipes/loading.pipe';
import { GroupWithAlerts } from '../../../data/basic.dto';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipeText],
  templateUrl: './group.component.html',
  styleUrl: './group.component.css'
})
export class GroupComponent {
  @Input() group: GroupWithAlerts | undefined;

  data: CheckGroup | undefined;

  status: Status | undefined;

  test: Check | undefined;

  ngOnInit(): void {
    console.log(this.group);
  }
}
