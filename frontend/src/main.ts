import { provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';

import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { AuthService } from './app/core/auth/auth.service';
import { loadRuntimeConfig, RUNTIME_CONFIG } from './app/core/config/runtime-config';

async function startApplication(): Promise<void> {
  const runtimeConfig = await loadRuntimeConfig();
  const authService = new AuthService(runtimeConfig.keycloak);

  await authService.initialize();

  await bootstrapApplication(AppComponent, {
    providers: [
      provideZonelessChangeDetection(),
      provideRouter(routes),
      { provide: RUNTIME_CONFIG, useValue: runtimeConfig },
      { provide: AuthService, useValue: authService },
    ],
  });
  void recordAuthenticatedSession(runtimeConfig.apiBaseUrl, authService);
}
async function recordAuthenticatedSession(
  apiBaseUrl: string,
  authService: AuthService,
): Promise<void> {
  const storageKey = 'alertify.auth.logged-session';
  if (sessionStorage.getItem(storageKey) === authService.sessionIdentifier) return;

  try {
    const token = await authService.getAccessToken();
    const response = await fetch(`${apiBaseUrl}/api/logs/login`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      throw new Error(`Login event failed with status ${response.status}.`);
    }
    sessionStorage.setItem(storageKey, authService.sessionIdentifier);
  } catch (error) {
    console.warn('Unable to record the authenticated browser session.', error);
  }
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
