import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { LocalizationService } from '../../core/i18n/localization.service';
import { PlaywrightRemoteViewComponent } from './playwright-remote-view.component';
import { playwrightBrowserLabel } from './playwright-wizard-api.service';

@Component({
  selector: 'app-playwright-view-popout',
  imports: [PlaywrightRemoteViewComponent],
  template: `<main><header><div><h1>{{ t('page') }}</h1>@if (activeBrowser()) { <span>{{ t('activeBrowser') }}: {{ activeBrowser() }}</span> }</div><button type="button" (click)="reattach()">{{ t('reattach') }}</button></header>@if (id) { <app-playwright-remote-view [draftId]="id" (elementSelected)="send('WIZARD_SELECT', { selection: $event })" (scrolled)="send('WIZARD_SCROLL', { delta: $event })" /> }<p>{{ t('popupHelp') }}</p></main>`,
  styles: `main { padding: 16px; } header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; } header > div { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; } header span { padding: 4px 9px; border: 1px solid #d9d6fe; border-radius: 20px; background: #f4f3ff; color: #5925dc; font-size: 12px; } h1 { margin: 0; font-size: 18px; } button { padding: 8px 12px; cursor: pointer; } p { color: #667085; font-size: 12px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlaywrightViewPopoutComponent {
  protected readonly id = inject(ActivatedRoute).snapshot.queryParamMap.get('id');
  private readonly engine = signal(inject(ActivatedRoute).snapshot.queryParamMap.get('engine'));
  protected readonly activeBrowser = computed(() => playwrightBrowserLabel(this.engine()));
  private readonly localization = inject(LocalizationService);
  constructor() {
    const listener = (event: MessageEvent): void => {
      if (event.origin === location.origin && event.source === window.opener && event.data?.id === this.id && event.data.type === 'WIZARD_ENGINE') this.engine.set(event.data.engine ?? null);
    };
    window.addEventListener('message', listener);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('message', listener));
  }
  protected t(key: string): string { return this.localization.translateDynamic('alerts.wizard.' + key); }
  protected send(type: string, payload: object): void { window.opener?.postMessage({ type, id: this.id, ...payload }, location.origin); }
  protected reattach(): void { this.send('WIZARD_REATTACH', {}); window.close(); }
}
