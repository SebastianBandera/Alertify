import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';
import { LoggerService } from './app/services/logger.service';

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => {
    const logger = new LoggerService();
    logger.error('Error al inicializar la aplicación', err);
  });