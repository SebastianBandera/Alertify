import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
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
import { StatusTickerComponent } from '../status-ticker/status-ticker.component';

interface NavigationItem {
  readonly labelKey: TranslationKey;
  readonly path: string;
  readonly icon: 'dashboard' | 'alerts' | 'procedures' | 'hooks' | 'status' | 'configs' | 'system-configs' | 'secrets' | 'logs';
}

@Component({
  selector: 'app-shell',
  imports: [FormsModule, RouterLink, RouterLinkActive, RouterOutlet, StatusTickerComponent],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  private static readonly SIDEBAR_COLLAPSED_STORAGE_KEY = 'alertify.sidebarCollapsed';
  private static readonly NAVIGATION_ORDER_STORAGE_KEY = 'alertify.navigationOrder';

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
      ? [{ labelKey: 'navigation.procedures' as const, path: '/procedures', icon: 'procedures' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.hooks' as const, path: '/hooks', icon: 'hooks' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.status' as const, path: '/status', icon: 'status' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.configs' as const, path: '/configs', icon: 'configs' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.systemConfigs' as const, path: '/system-configs', icon: 'system-configs' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.secrets' as const, path: '/secrets', icon: 'secrets' as const }]
      : []),
    ...(this.authService.isAdmin
      ? [{ labelKey: 'navigation.logs' as const, path: '/logs', icon: 'logs' as const }]
      : []),
  ];
  protected readonly searchTerm = signal('');
  protected readonly sidebarCollapsed = signal(this.restoreSidebarCollapsed());
  protected readonly orderedNavigationItems = signal(this.restoreNavigationOrder());
  protected readonly draggedNavigationPath = signal<string | null>(null);
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('navigationSearchInput');
  private readonly activeTitleKey = signal<TranslationKey>(this.titleKeyForUrl(this.router.url));
  protected readonly filteredNavigationItems = computed(() => {
    const query = this.searchTerm().trim().toLowerCase();
    return query
      ? this.orderedNavigationItems().filter((item) =>
          this.localization.translate(item.labelKey).toLowerCase().includes(query),
        )
      : this.orderedNavigationItems();
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

  protected toggleSidebar(): void {
    const collapsed = !this.sidebarCollapsed();
    this.sidebarCollapsed.set(collapsed);

    try {
      localStorage.setItem(AppShellComponent.SIDEBAR_COLLAPSED_STORAGE_KEY, String(collapsed));
    } catch {
      // The navigation remains usable when browser storage is unavailable.
    }
  }

  protected expandSidebarAndFocusSearch(): void {
    if (this.sidebarCollapsed()) {
      this.toggleSidebar();
    }

    setTimeout(() => this.searchInput()?.nativeElement.focus());
  }

  protected startNavigationDrag(event: DragEvent, item: NavigationItem): void {
    if (this.searchTerm() || !window.matchMedia('(min-width: 801px)').matches) {
      event.preventDefault();
      return;
    }

    this.draggedNavigationPath.set(item.path);
    event.dataTransfer?.setData('text/plain', item.path);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  protected allowNavigationDrop(event: DragEvent, item: NavigationItem): void {
    if (this.draggedNavigationPath() && this.draggedNavigationPath() !== item.path) {
      event.preventDefault();
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = 'move';
      }
    }
  }

  protected reorderNavigation(event: DragEvent, target: NavigationItem): void {
    event.preventDefault();
    const draggedPath = this.draggedNavigationPath();
    this.draggedNavigationPath.set(null);
    if (!draggedPath || draggedPath === target.path) {
      return;
    }

    const items = this.orderedNavigationItems();
    const draggedIndex = items.findIndex((item) => item.path === draggedPath);
    const targetIndex = items.findIndex((item) => item.path === target.path);
    if (draggedIndex < 0 || targetIndex < 0) {
      return;
    }

    const reorderedItems = [...items];
    const [draggedItem] = reorderedItems.splice(draggedIndex, 1);
    const insertionIndex = draggedIndex < targetIndex ? targetIndex - 1 : targetIndex;
    reorderedItems.splice(insertionIndex, 0, draggedItem);
    this.orderedNavigationItems.set(reorderedItems);
    this.persistNavigationOrder(reorderedItems);
  }

  protected finishNavigationDrag(): void {
    this.draggedNavigationPath.set(null);
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
    if (url.startsWith('/procedures')) {
      return 'navigation.procedures';
    }
    if (url.startsWith('/hooks')) {
      return 'navigation.hooks';
    }
    if (url.startsWith('/status')) {
      return 'navigation.status';
    }
    if (url.startsWith('/system-configs')) {
      return 'navigation.systemConfigs';
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

  private restoreSidebarCollapsed(): boolean {
    try {
      return localStorage.getItem(AppShellComponent.SIDEBAR_COLLAPSED_STORAGE_KEY) === 'true';
    } catch {
      return false;
    }
  }

  private restoreNavigationOrder(): readonly NavigationItem[] {
    try {
      const storedOrder = JSON.parse(localStorage.getItem(AppShellComponent.NAVIGATION_ORDER_STORAGE_KEY) ?? 'null');
      if (!Array.isArray(storedOrder) || storedOrder.some((path) => typeof path !== 'string')) {
        return this.navigationItems;
      }

      const itemsByPath = new Map(this.navigationItems.map((item) => [item.path, item]));
      const restoredPaths = new Set<string>();
      const restoredItems = storedOrder.reduce<NavigationItem[]>((items, path) => {
        const item = itemsByPath.get(path);
        if (item && !restoredPaths.has(item.path)) {
          restoredPaths.add(item.path);
          items.push(item);
        }
        return items;
      }, []);
      return [...restoredItems, ...this.navigationItems.filter((item) => !restoredPaths.has(item.path))];
    } catch {
      return this.navigationItems;
    }
  }

  private persistNavigationOrder(items: readonly NavigationItem[]): void {
    try {
      localStorage.setItem(AppShellComponent.NAVIGATION_ORDER_STORAGE_KEY, JSON.stringify(items.map((item) => item.path)));
    } catch {
      // The navigation remains usable when browser storage is unavailable.
    }
  }
}
