import { inject, Injectable } from '@angular/core';

import { LogApiService } from '../api/log-api.service';
import { AuthService } from './auth.service';

/** Shared authenticated-session actions used by both application layouts. */
@Injectable({ providedIn: 'root' })
export class SessionActionsService {
  private readonly authService = inject(AuthService);
  private readonly logApi = inject(LogApiService);

  async logout(): Promise<void> {
    try {
      await this.logApi.recordLogout();
    } catch (error) {
      console.warn('Unable to record the logout event.', error);
    }
    await this.authService.logout();
  }
}
