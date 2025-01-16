import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class SpinnerService {
  private visibilitySubject = new BehaviorSubject<boolean>(false);

  private isVisible$ = this.visibilitySubject.asObservable();

  constructor() { }

  show() {
    this.visibilitySubject.next(true);
  }

  hide() {
    this.visibilitySubject.next(false);
  }

  getObservable$() {
    return this.isVisible$;
  }
}
