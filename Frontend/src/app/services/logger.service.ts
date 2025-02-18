import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class LoggerService {

  constructor() { }

  private static formatTimestamp(): string {
    const now = new Date();

    const day = String(now.getDate()).padStart(2, '0');
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const year = now.getFullYear();
  
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');
    const seconds = String(now.getSeconds()).padStart(2, '0');
  
    return `${day}/${month}/${year} ${hours}:${minutes}:${seconds}`;
  }

  log(message: any, ...optionalParams: any[]): void {
    console.log(`[LOG-${LoggerService.formatTimestamp()}]`, message, ...optionalParams);
  }

  error(message: any, ...optionalParams: any[]): void {
    console.error(`[ERR-${LoggerService.formatTimestamp()}]`, message, ...optionalParams);
  }

  info(message: any, ...optionalParams: any[]): void {
    console.info(`[INF-${LoggerService.formatTimestamp()}]`, message, ...optionalParams);
  }
  
  warn(message: any, ...optionalParams: any[]): void {
    console.warn(`[WAR-${LoggerService.formatTimestamp()}]`, message, ...optionalParams);
  }
  
  debug(message: any, ...optionalParams: any[]): void {
    console.debug(`[DEB-${LoggerService.formatTimestamp()}]`, message, ...optionalParams);
  }

  performance_now(): number {
    try {
      return performance.now();
    } catch (error) {
      return 0;
    }
  }

  log_performance_now(start: number, end: number): void {
    this.debug(`Tiempo transcurrido: ${(end - start)} ms`);
  }
}
