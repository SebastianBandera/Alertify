import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { JsonPipe } from '@angular/common';

import {
  SystemConfiguration,
  SystemConfigurationApiService,
} from '../../core/api/system-configuration-api.service';
import { ApiRequestError } from '../../core/api/configuration-api.service';
import { SystemStatusApiService, SystemStatusSummary } from '../../core/api/system-status-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

const KEY_PART_NAME = 'KEY_PART';
const QUIET_HOURS_NAME = 'CRON_QUIET_HOURS';
const MAINTENANCE_MODE_NAME = 'MAINTENANCE_MODE';
const DASHBOARD_WINDOW_NAME = 'DASHBOARD_HISTORY_WINDOW';
/* Mirrors the range the backend accepts for the dashboard look-back window. */
const DASHBOARD_WINDOW_MIN_DAYS = 1;
const DASHBOARD_WINDOW_MAX_DAYS = 365;
const DASHBOARD_WINDOW_DEFAULT_DAYS = 10;

interface KeyPartForm {
  manualEntryOpen: boolean;
  newValue: string;
  confirmed: boolean;
}

interface QuietHoursValue {
  enabled: boolean;
  start: string;
  end: string;
}

interface QuietHoursForm {
  enabled: boolean;
  start: string;
  end: string;
}

interface MaintenanceModeValue {
  enabled: boolean;
}

interface MaintenanceModeForm {
  enabled: boolean;
}

interface DashboardWindowForm {
  days: number | null;
}

function emptyKeyPartForm(): KeyPartForm {
  return { manualEntryOpen: false, newValue: '', confirmed: false };
}

function emptyQuietHoursForm(): QuietHoursForm {
  return { enabled: false, start: '23:00', end: '07:00' };
}

function parseQuietHoursValue(value: unknown): QuietHoursValue {
  const raw = (value ?? {}) as Partial<QuietHoursValue>;
  return {
    enabled: raw.enabled === true,
    start: typeof raw.start === 'string' ? raw.start : '23:00',
    end: typeof raw.end === 'string' ? raw.end : '07:00',
  };
}

function emptyMaintenanceModeForm(): MaintenanceModeForm {
  return { enabled: false };
}

function parseMaintenanceModeValue(value: unknown): MaintenanceModeValue {
  const raw = (value ?? {}) as Partial<MaintenanceModeValue>;
  return { enabled: raw.enabled === true };
}

function parseDashboardWindowValue(value: unknown): DashboardWindowForm {
  const days = (value as { days?: unknown } | null)?.days;
  return { days: typeof days === 'number' && Number.isInteger(days) ? days : DASHBOARD_WINDOW_DEFAULT_DAYS };
}

function isValidDashboardWindowDays(days: number | null): days is number {
  return days !== null && Number.isInteger(days) && days >= DASHBOARD_WINDOW_MIN_DAYS && days <= DASHBOARD_WINDOW_MAX_DAYS;
}

@Component({
  selector: 'app-system-configs',
  imports: [FormsModule, JsonPipe],
  templateUrl: './system-configs.component.html',
  styleUrl: '../configs/configs.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SystemConfigsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly quietHoursName = QUIET_HOURS_NAME;
  protected readonly dashboardWindowMinDays = DASHBOARD_WINDOW_MIN_DAYS;
  protected readonly dashboardWindowMaxDays = DASHBOARD_WINDOW_MAX_DAYS;

  private readonly api = inject(SystemConfigurationApiService);
  private readonly statusApi = inject(SystemStatusApiService);

  protected readonly configurations = signal<readonly SystemConfiguration[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly keyPartConfiguration = signal<SystemConfiguration | null>(null);
  protected readonly keyPartForm = signal<KeyPartForm>(emptyKeyPartForm());
  protected readonly keyPartError = signal<string | null>(null);

  protected readonly quietHoursConfiguration = signal<SystemConfiguration | null>(null);
  protected readonly quietHoursForm = signal<QuietHoursForm>(emptyQuietHoursForm());
  protected readonly quietHoursError = signal<string | null>(null);

  protected readonly maintenanceModeConfiguration = signal<SystemConfiguration | null>(null);
  protected readonly maintenanceModeForm = signal<MaintenanceModeForm>(emptyMaintenanceModeForm());
  protected readonly maintenanceModeError = signal<string | null>(null);
  protected readonly systemStatusSummary = signal<SystemStatusSummary | null>(null);
  protected readonly activeExecutionsTotal = computed(() => {
    const summary = this.systemStatusSummary();
    return summary === null ? null : summary.activeAlertExecutions + summary.activeProcedureExecutions;
  });

  protected readonly dashboardWindowConfiguration = signal<SystemConfiguration | null>(null);
  protected readonly dashboardWindowForm = signal<DashboardWindowForm>({ days: DASHBOARD_WINDOW_DEFAULT_DAYS });
  protected readonly dashboardWindowError = signal<string | null>(null);
  protected readonly dashboardWindowValid = computed(() => isValidDashboardWindowDays(this.dashboardWindowForm().days));

  protected readonly otherConfigurations = signal<readonly SystemConfiguration[]>([]);

  async ngOnInit(): Promise<void> {
    await this.loadConfigurations();
  }

  protected async loadConfigurations(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await this.api.listSystemConfigurations(0, 100);
      this.configurations.set(result.content);

      const keyPart = result.content.find((configuration) => configuration.name === KEY_PART_NAME) ?? null;
      this.keyPartConfiguration.set(keyPart);
      this.keyPartForm.set(emptyKeyPartForm());

      const quietHours = result.content.find((configuration) => configuration.name === QUIET_HOURS_NAME) ?? null;
      this.quietHoursConfiguration.set(quietHours);
      this.quietHoursForm.set(parseQuietHoursValue(quietHours?.value));

      const maintenanceMode = result.content.find((configuration) => configuration.name === MAINTENANCE_MODE_NAME) ?? null;
      this.maintenanceModeConfiguration.set(maintenanceMode);
      this.maintenanceModeForm.set(parseMaintenanceModeValue(maintenanceMode?.value));

      const dashboardWindow = result.content.find((configuration) => configuration.name === DASHBOARD_WINDOW_NAME) ?? null;
      this.dashboardWindowConfiguration.set(dashboardWindow);
      this.dashboardWindowForm.set(parseDashboardWindowValue(dashboardWindow?.value));

      this.otherConfigurations.set(
        result.content.filter(
          (configuration) => configuration.name !== KEY_PART_NAME && configuration.name !== QUIET_HOURS_NAME
            && configuration.name !== MAINTENANCE_MODE_NAME && configuration.name !== DASHBOARD_WINDOW_NAME,
        ),
      );
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }

    try {
      this.systemStatusSummary.set(await this.statusApi.summary());
    } catch {
      this.systemStatusSummary.set(null);
    }
  }

  // -- KEY_PART section --

  protected patchKeyPartForm(patch: Partial<KeyPartForm>): void {
    this.keyPartForm.update((form) => ({ ...form, ...patch }));
    this.keyPartError.set(null);
  }

  protected toggleKeyPartManualEntry(): void {
    this.keyPartForm.update((form) => ({
      ...form,
      manualEntryOpen: !form.manualEntryOpen,
      newValue: '',
      confirmed: false,
    }));
    this.keyPartError.set(null);
  }

  protected async regenerateKeyPart(): Promise<void> {
    const configuration = this.keyPartConfiguration();
    const form = this.keyPartForm();
    if (!configuration || !form.confirmed || this.saving()) return;

    this.saving.set(true);
    this.keyPartError.set(null);
    this.notice.set(null);
    try {
      await this.api.regenerateSystemConfiguration(configuration.id, configuration.version);
      this.notice.set(this.localization.translate('systemConfigs.keyPart.regenerated'));
      await this.loadConfigurations();
    } catch (error) {
      this.keyPartError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async saveKeyPartManualValue(): Promise<void> {
    const configuration = this.keyPartConfiguration();
    const form = this.keyPartForm();
    if (!configuration) return;
    if (!form.newValue || !form.confirmed) return;

    this.saving.set(true);
    this.keyPartError.set(null);
    this.notice.set(null);
    try {
      await this.api.updateSystemConfiguration(configuration.id, {
        version: configuration.version,
        value: form.newValue,
      });
      this.notice.set(this.localization.translate('systemConfigs.keyPart.updated'));
      await this.loadConfigurations();
    } catch (error) {
      this.keyPartError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  // -- CRON_QUIET_HOURS section --

  protected patchQuietHoursForm(patch: Partial<QuietHoursForm>): void {
    this.quietHoursForm.update((form) => ({ ...form, ...patch }));
    this.quietHoursError.set(null);
  }

  protected async saveQuietHours(): Promise<void> {
    const configuration = this.quietHoursConfiguration();
    const form = this.quietHoursForm();
    if (!configuration || this.saving()) return;

    this.saving.set(true);
    this.quietHoursError.set(null);
    this.notice.set(null);
    try {
      await this.api.updateSystemConfiguration(configuration.id, {
        version: configuration.version,
        value: { enabled: form.enabled, start: form.start, end: form.end },
      });
      this.notice.set(this.localization.translate('systemConfigs.quietHours.saved'));
      await this.loadConfigurations();
    } catch (error) {
      this.quietHoursError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  // -- MAINTENANCE_MODE section --

  protected patchMaintenanceModeForm(patch: Partial<MaintenanceModeForm>): void {
    this.maintenanceModeForm.update((form) => ({ ...form, ...patch }));
    this.maintenanceModeError.set(null);
  }

  protected async saveMaintenanceMode(): Promise<void> {
    const configuration = this.maintenanceModeConfiguration();
    const form = this.maintenanceModeForm();
    if (!configuration || this.saving()) return;

    this.saving.set(true);
    this.maintenanceModeError.set(null);
    this.notice.set(null);
    try {
      await this.api.updateSystemConfiguration(configuration.id, {
        version: configuration.version,
        value: { enabled: form.enabled },
      });
      this.notice.set(this.localization.translate('systemConfigs.maintenanceMode.saved'));
      await this.loadConfigurations();
    } catch (error) {
      this.maintenanceModeError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  // -- DASHBOARD_HISTORY_WINDOW section --

  protected patchDashboardWindowForm(days: number | null): void {
    this.dashboardWindowForm.set({ days });
    this.dashboardWindowError.set(null);
  }

  protected async saveDashboardWindow(): Promise<void> {
    const configuration = this.dashboardWindowConfiguration();
    const days = this.dashboardWindowForm().days;
    if (!configuration || this.saving()) return;
    if (!isValidDashboardWindowDays(days)) {
      this.dashboardWindowError.set(this.dashboardWindowRangeMessage());
      return;
    }

    this.saving.set(true);
    this.dashboardWindowError.set(null);
    this.notice.set(null);
    try {
      await this.api.updateSystemConfiguration(configuration.id, {
        version: configuration.version,
        value: { days },
      });
      this.notice.set(this.localization.translate('systemConfigs.dashboardWindow.saved'));
      await this.loadConfigurations();
    } catch (error) {
      this.dashboardWindowError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected dashboardWindowRangeMessage(): string {
    return this.localization.translate('systemConfigs.dashboardWindow.invalid')
      .replace('{min}', String(DASHBOARD_WINDOW_MIN_DAYS))
      .replace('{max}', String(DASHBOARD_WINDOW_MAX_DAYS));
  }

  protected formatDate(value: string): string {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return value;
    return new Intl.DateTimeFormat(this.localization.locale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(parsed);
  }

  private errorMessage(error: unknown): string {
    return error instanceof ApiRequestError || error instanceof Error ? error.message : String(error);
  }
}
