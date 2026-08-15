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
  readonly marker: string;
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
    { label: 'Configs', path: '/configs', marker: 'C' },
    { label: 'Secrets', path: '/secrets', marker: 'S' },
    { label: 'Logs', path: '/logs', marker: 'L' },
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
