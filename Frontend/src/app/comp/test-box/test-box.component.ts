import { CommonModule } from '@angular/common';
import { Component, Input, ViewEncapsulation } from '@angular/core';

@Component({
  selector: 'app-test-box',
  imports: [CommonModule],
  templateUrl: './test-box.component.html',
  styleUrl: './test-box.component.css',
  encapsulation: ViewEncapsulation.None
})
export class TestBoxComponent {
  @Input() text: string = 'Texto por defecto';
  @Input() backgroundColor: string = 'lightblue';
}
