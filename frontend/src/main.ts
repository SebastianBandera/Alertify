import { provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';

import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { AuthService } from './app/core/auth/auth.service';
import { loadRuntimeConfig } from './app/core/config/runtime-config';

async function startApplication(): Promise<void> {
  const runtimeConfig = await loadRuntimeConfig();
  const authService = new AuthService(runtimeConfig.keycloak);

  await authService.initialize();

  await bootstrapApplication(AppComponent, {
    providers: [
      provideZonelessChangeDetection(),
      provideRouter(routes),
      { provide: AuthService, useValue: authService },
    ],
  });
}

function showStartupError(error: unknown): void {
  console.error('Unable to start Alertify.', error);

  const root = document.querySelector('app-root');
  if (root) {
    root.innerHTML = `
      <main class="bootstrap-screen bootstrap-screen--error" role="alert">
        <div class="bootstrap-mark" aria-hidden="true">!</div>
        <div>
          <strong>Unable to start Alertify</strong>
          <p>Check the runtime configuration and identity service, then reload the page.</p>
        </div>
      </main>
    `;
  }
}

void startApplication().catch(showStartupError);
