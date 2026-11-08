import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';
import { ConfigurationPreviewService } from './configuration-preview.service';

@Component({
  selector: 'app-configuration-preview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (configurationId() !== null) {
      <section aria-live="polite">
        <strong>{{ localization.translate('configs.preview.label') }}</strong>
        @if (loading()) {
          <span>{{ localization.translate('configs.expression.evaluating') }}</span>
        } @else if (failed()) {
          <span>{{ localization.translate('configs.preview.error') }}</span>
          <button type="button" (click)="retry()">{{ localization.translate('configs.preview.retry') }}</button>
        } @else {
          <pre>{{ value() === '' ? localization.translate('configs.preview.empty') : value() }}</pre>
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; min-width: 0; grid-column: 1 / -1; }
    section { margin-top: 6px; font-size: 12px; color: var(--text-muted, #63718a); }
    strong { display: block; margin-bottom: 4px; }
    pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 180px; overflow: auto; font: inherit; }
    button { border: 0; padding: 0 4px; background: transparent; color: var(--primary, #6d5dfc); cursor: pointer; }
    button:hover, button:focus-visible { text-decoration: underline; }
  `,
})
export class ConfigurationPreviewComponent {
  readonly configurationId = input<number | null>(null);
  protected readonly localization = inject(LocalizationService);
  private readonly previews = inject(ConfigurationPreviewService);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  protected readonly value = signal('');
  private readonly attempts = signal(0);

  constructor() {
    effect((onCleanup) => {
      const id = this.configurationId();
      this.previews.revision();
      this.attempts();
      let current = true;
      onCleanup(() => { current = false; });
      this.value.set('');
      this.failed.set(false);
      this.loading.set(id !== null);
      if (id === null) return;

      void this.previews.get(id).then((value) => {
        if (current) this.value.set(value);
      }).catch(() => {
        if (current) this.failed.set(true);
      }).finally(() => {
        if (current) this.loading.set(false);
      });
    });
  }

  protected retry(): void {
    this.attempts.update((value) => value + 1);
  }
}
