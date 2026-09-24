import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';

import { DashboardAlertCard } from '../../features/dashboard/dashboard-card';
import { LocalizationService } from '../i18n/localization.service';
import { TranslationKey } from '../i18n/localization.types';

const BODY_MAX_LENGTH = 120;

const STATUS_LABEL_KEYS: Readonly<Record<'WARN' | 'ERROR', TranslationKey>> = {
  WARN: 'dashboard.notification.WARN',
  ERROR: 'dashboard.notification.ERROR',
};

/**
 * Native browser notifications for alert results, so an operator working in
 * another section (or another tab) still learns about a WARN/ERROR outcome.
 */
@Injectable({ providedIn: 'root' })
export class BrowserNotificationService {
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly localization = inject(LocalizationService);
  private permissionRequested = false;

  /**
   * Asks for permission on the first user gesture: browsers treat a request
   * made without one as a low-priority prompt or ignore it outright.
   */
  ensurePermission(): void {
    const window = this.document.defaultView;
    if (!window || !('Notification' in window) || window.Notification.permission !== 'default' || this.permissionRequested) return;

    this.permissionRequested = true;
    const request = (): void => {
      this.document.removeEventListener('pointerdown', request);
      this.document.removeEventListener('keydown', request);
      void window.Notification.requestPermission();
    };
    this.document.addEventListener('pointerdown', request);
    this.document.addEventListener('keydown', request);
  }

  notifyAlert(card: DashboardAlertCard): void {
    const window = this.document.defaultView;
    const execution = card.lastExecution;
    if (!window || !('Notification' in window) || window.Notification.permission !== 'granted') return;
    if (execution === null) return;
    const status = execution.status;
    if (status === 'SUCCESS') return;

    const notification = new window.Notification(card.alert.name, {
      body: this.body(card, status),
      tag: `alertify-alert-${card.alert.id}`,
      icon: 'icons/alertify.svg',
    });
    notification.onclick = () => {
      window.focus();
      notification.close();
      void this.router.navigate(['/dashboard'], { queryParams: { alert: card.alert.id } });
    };
  }

  private body(card: DashboardAlertCard, executionStatus: 'WARN' | 'ERROR'): string {
    const status = this.localization.translate(STATUS_LABEL_KEYS[executionStatus]);
    if (!card.alert.tags.length)
      return `${status} · ${this.localization.translate('dashboard.notification.noTags')}`;

    const prefix = `${status} · ${this.localization.translate('dashboard.notification.tags')}: `;
    let body = prefix;
    for (const tag of card.alert.tags) {
      const separator = body === prefix ? '' : ', ';
      if (`${body}${separator}${tag.name}`.length > BODY_MAX_LENGTH)
        return `${body}${separator}…`;

      body += `${separator}${tag.name}`;
    }
    return body;
  }
}
