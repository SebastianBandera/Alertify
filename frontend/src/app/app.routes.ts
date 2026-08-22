import { Routes } from '@angular/router';

import { AppShellComponent } from './layout/app-shell/app-shell.component';
import { adminGuard } from './core/auth/admin.guard';

export const routes: Routes = [
  {
    path: '',
    component: AppShellComponent,
    children: [
      {
        path: 'dashboard',
        title: 'Dashboard | Alertify',
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then(
            (component) => component.DashboardComponent,
          ),
      },
      {
        path: 'configs',
        title: 'Configs | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/configs/configs.component').then(
            (component) => component.ConfigsComponent,
          ),
      },
      {
        path: 'secrets',
        title: 'Secrets | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/secrets/secrets.component').then(
            (component) => component.SecretsComponent,
          ),
      },
      {
        path: 'logs',
        title: 'Logs | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/logs/logs.component').then(
            (component) => component.LogsComponent,
          ),
      },
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
    ],
  },
  { path: '**', redirectTo: 'dashboard' },
];
