import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class ParserService {

  public stringToDate(str: string): Date {
    return new Date(str);
  }

  public isValidDate(date: Date): boolean {
    return !isNaN(date.getTime());
  }
}
