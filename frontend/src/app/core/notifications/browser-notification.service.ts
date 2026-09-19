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
    if (execution === null || execution.status === 'SUCCESS') return;

    const notification = new window.Notification(card.alert.name, {
      body: `${this.localization.translate(STATUS_LABEL_KEYS[execution.status])} · ${this.body(card)}`,
      tag: `alertify-alert-${card.alert.id}`,
      icon: 'icons/alertify.svg',
    });
    notification.onclick = () => {
      window.focus();
      notification.close();
      void this.router.navigate(['/dashboard'], { queryParams: { alert: card.alert.id } });
    };
  }

  private body(card: DashboardAlertCard): string {
    const execution = card.lastExecution;
    let message = '';
    if (execution?.status === 'ERROR') message = execution.errorMessage ?? execution.errorType ?? '';
    else if (execution?.statusMessage != null) message = JSON.stringify(execution.statusMessage);
    if (!message) return this.localization.translate('dashboard.notification.noMessage');
    return message.length > BODY_MAX_LENGTH ? `${message.slice(0, BODY_MAX_LENGTH - 1)}…` : message;
  }
}
