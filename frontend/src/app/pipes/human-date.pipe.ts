import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'humanDate'
})
export class HumanDatePipe implements PipeTransform {

  transform(value: Date | string | null | undefined): string {
    if (!value) return '';

    const date = new Date(value);
    if (isNaN(date.getTime())) return 'Invalid date';

    return date.toLocaleString('en-US', {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      hour12: true
    });
  }

}
