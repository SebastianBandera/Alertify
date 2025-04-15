import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'arrayLen'
})
export class ArrayLenPipe implements PipeTransform {

  transform(value: any[] | null | undefined): string {
    if (!value) return '';

    if(value.length == 0) return '';

    return "(" + value.length + ")";
  }

}
