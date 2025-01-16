import { Component } from '@angular/core';
import { SpinnerService } from './spinner.service';
import { CommonModule } from '@angular/common';

@Component({
    selector: 'app-spinner',
    imports: [CommonModule],
    templateUrl: './spinner.component.html',
    styleUrl: './spinner.component.css'
})
export class SpinnerComponent {
  isVisible: boolean = false;

  constructor(private spinner:SpinnerService) {}

  ngOnInit(): void {
    this.spinner.getObservable$().subscribe({
      next: (response) => {
        this.isVisible = response;
      } 
    });
  }
}
