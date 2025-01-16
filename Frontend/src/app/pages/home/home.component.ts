import { Component } from '@angular/core';
import { MaineventsComponent } from '../mainevents/mainevents.component';

@Component({
    selector: 'app-home',
    imports: [MaineventsComponent],
    templateUrl: './home.component.html',
    styleUrl: './home.component.css'
})
export class HomeComponent {

}
