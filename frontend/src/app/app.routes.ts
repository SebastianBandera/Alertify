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
        path: 'alerts',
        title: 'Alerts | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/alerts/alerts.component').then(
            (component) => component.AlertsComponent,
          ),
      },
      {
        path: 'procedures',
        title: 'Procedures | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/procedures/procedures.component').then(
            (component) => component.ProceduresComponent,
          ),
      },
      {
        path: 'hooks',
        title: 'Hooks | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/hooks/hooks.component').then(
            (component) => component.HooksComponent,
          ),
      },
      {
        path: 'status',
        title: 'Status | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/status/status.component').then(
            (component) => component.StatusComponent,
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
        path: 'system-configs',
        title: 'System Configs | Alertify',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/system-configs/system-configs.component').then(
            (component) => component.SystemConfigsComponent,
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
