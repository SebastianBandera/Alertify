import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Title } from '@angular/platform-browser';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { LogApiService } from '../../core/api/log-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { TranslationKey } from '../../core/i18n/localization.types';

interface NavigationItem {
  readonly labelKey: TranslationKey;
  readonly path: string;
  readonly icon: 'dashboard' | 'alerts' | 'status' | 'configs' | 'secrets' | 'logs';
}

@Component({
  selector: 'app-shell',
  imports: [FormsModule, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  protected readonly authService = inject(AuthService);
  protected readonly localization = inject(LocalizationService);
  private readonly logApi = inject(LogApiService);
  private readonly router = inject(Router);
  private readonly title = inject(Title);
  protected readonly navigationItems: readonly NavigationItem[] = [
    { labelKey: 'navigation.dashboard', path: '/dashboard', icon: 'dashboard' },
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.alerts' as const, path: '/alerts', icon: 'alerts' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.status' as const, path: '/status', icon: 'status' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.configs' as const, path: '/configs', icon: 'configs' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.secrets' as const, path: '/secrets', icon: 'secrets' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.logs' as const, path: '/logs', icon: 'logs' as const }]
      : []),
  ];
  protected readonly searchTerm = signal('');
  private readonly activeTitleKey = signal<TranslationKey>(this.titleKeyForUrl(this.router.url));
  protected readonly filteredNavigationItems = computed(() => {
    const query = this.searchTerm().trim().toLowerCase();
    return query
      ? this.navigationItems.filter((item) =>
          this.localization.translate(item.labelKey).toLowerCase().includes(query),
        )
      : this.navigationItems;
  });

  constructor() {
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => this.activeTitleKey.set(this.titleKeyForUrl(event.urlAfterRedirects)));

    effect(() => {
      this.title.setTitle(`${this.localization.translate(this.activeTitleKey())} | Alertify`);
    });
  }

  protected updateSearch(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
  }

  protected updateLocale(locale: string): void {
    this.localization.setLocale(locale);
  }

  protected async logout(): Promise<void> {
    try {
      await this.logApi.recordLogout();
    } catch (error) {
      console.warn('Unable to record the logout event.', error);
    }
    await this.authService.logout();
  }

  private titleKeyForUrl(url: string): TranslationKey {
    if (url.startsWith('/alerts')) {
      return 'navigation.alerts';
    }
    if (url.startsWith('/status')) {
      return 'navigation.status';
    }
    if (url.startsWith('/configs')) {
      return 'navigation.configs';
    }
    if (url.startsWith('/secrets')) {
      return 'navigation.secrets';
    }
    if (url.startsWith('/logs')) {
      return 'navigation.logs';
    }
    return 'navigation.dashboard';
  }
}
