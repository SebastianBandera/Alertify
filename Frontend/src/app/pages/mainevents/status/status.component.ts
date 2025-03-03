import { Component, Input, TemplateRef, ViewChild } from '@angular/core';
import { Status } from '../../../data/status.enum';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-status',
  imports: [CommonModule],
  templateUrl: './status.component.html',
  styleUrl: './status.component.css'
})
export class StatusComponent {
  @Input() status!: Status | undefined;

  @ViewChild('status_ok', { static: true }) status_ok!: TemplateRef<any>;
  @ViewChild('status_warn', { static: true }) status_warn!: TemplateRef<any>;
  @ViewChild('status_error', { static: true }) status_error!: TemplateRef<any>;

  getTemplate(): TemplateRef<any> | null {
    switch (this.status) {
      case Status.OK:
        return this.status_ok;
      case Status.WARN:
        return this.status_warn;
      case Status.ERROR:
        return this.status_error;
      default:
        return null;
    }
  }
}
