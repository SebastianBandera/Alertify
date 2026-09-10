import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { JsonPipe } from '@angular/common';

import {
  SystemConfiguration,
  SystemConfigurationApiService,
} from '../../core/api/system-configuration-api.service';
import { ApiRequestError } from '../../core/api/configuration-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

const KEY_PART_NAME = 'KEY_PART';
const QUIET_HOURS_NAME = 'CRON_QUIET_HOURS';

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

  private readonly api = inject(SystemConfigurationApiService);

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

      this.otherConfigurations.set(
        result.content.filter(
          (configuration) => configuration.name !== KEY_PART_NAME && configuration.name !== QUIET_HOURS_NAME,
        ),
      );
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
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
