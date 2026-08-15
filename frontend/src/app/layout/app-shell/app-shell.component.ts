import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';

interface NavigationItem {
  readonly label: string;
  readonly path: string;
  readonly icon: 'dashboard' | 'configs' | 'secrets' | 'logs';
}

@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  protected readonly authService = inject(AuthService);
  protected readonly navigationItems: readonly NavigationItem[] = [
    { label: 'Dashboard', path: '/dashboard', icon: 'dashboard' },
    { label: 'Configs', path: '/configs', icon: 'configs' },
    { label: 'Secrets', path: '/secrets', icon: 'secrets' },
    { label: 'Logs', path: '/logs', icon: 'logs' },
  ];
  protected readonly searchTerm = signal('');
  protected readonly filteredNavigationItems = computed(() => {
    const query = this.searchTerm().trim().toLowerCase();
    return query
      ? this.navigationItems.filter((item) => item.label.toLowerCase().includes(query))
      : this.navigationItems;
  });

  protected updateSearch(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
  }

  protected logout(): void {
    void this.authService.logout();
  }
}
