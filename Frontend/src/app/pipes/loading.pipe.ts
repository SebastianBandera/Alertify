import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'loading',
})
export class LoadingPipe implements PipeTransform {
  transform(value: any, loadingText: string = 'Cargando...'): any {
    return value === undefined || value === null ? loadingText : value;
  }
}