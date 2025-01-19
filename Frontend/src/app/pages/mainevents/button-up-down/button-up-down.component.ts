import { CommonModule } from '@angular/common';
import { Component, TemplateRef, ViewChild } from '@angular/core';

@Component({
  selector: 'app-button-up-down',
  imports: [CommonModule],
  templateUrl: './button-up-down.component.html',
  styleUrl: './button-up-down.component.css'
})
export class ButtonUpDownComponent {
  private status_up: boolean = true;

  @ViewChild('boton_down', { static: true }) boton_down!: TemplateRef<any>;
  @ViewChild('boton_up', { static: true }) boton_up!: TemplateRef<any>;

    getTemplate(): TemplateRef<any> | null {
      switch (this.status_up) {
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
    }
}
