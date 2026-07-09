import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { LoggerService } from './services/logger.service';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';

export const appConfig: ApplicationConfig = {
  providers: [
    LoggerService, 
    provideZoneChangeDetection({ eventCoalescing: true }), 
    provideHttpClient(
      withFetch(),
      withInterceptors([]),
    ),
    provideRouter(routes)]
};
