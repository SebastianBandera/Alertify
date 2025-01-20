import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'loadingText',
})
export class LoadingPipeText implements PipeTransform {
  transform(value: any, loadingText: string = 'Cargando...'): any {
    return value === undefined || value === null ? loadingText : value;
  }
}