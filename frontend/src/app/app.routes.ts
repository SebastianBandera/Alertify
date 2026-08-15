import { Routes } from '@angular/router';

import { AppShellComponent } from './layout/app-shell/app-shell.component';

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
        loadComponent: () =>
          import('./features/configs/configs.component').then(
            (component) => component.ConfigsComponent,
          ),
      },
      {
        path: 'secrets',
        title: 'Secrets | Alertify',
        loadComponent: () =>
          import('./features/secrets/secrets.component').then(
            (component) => component.SecretsComponent,
          ),
      },
      {
        path: 'logs',
        title: 'Logs | Alertify',
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
