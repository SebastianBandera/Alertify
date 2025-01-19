import { Component } from '@angular/core';
import { CheckGroup } from '../../../data/check-group';
import { Status } from '../../../data/status';
import { Check } from '../../../data/check';
import { StatusComponent } from '../status/status.component';
import { ButtonUpDownComponent } from '../button-up-down/button-up-down.component';
import { CommonModule } from '@angular/common';
import { LoadingPipe } from '../../../pipes/loading.pipe';

@Component({
  selector: 'app-group',
  imports: [CommonModule, StatusComponent, ButtonUpDownComponent, LoadingPipe],
  templateUrl: './group.component.html',
  styleUrl: './group.component.css'
})
export class GroupComponent {
  data: CheckGroup | undefined;

  status: Status | undefined;

  test: Check | undefined;
}
