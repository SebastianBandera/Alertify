import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  SystemConfiguration,
  SystemConfigurationApiService,
} from '../../core/api/system-configuration-api.service';
import { ApiRequestError } from '../../core/api/configuration-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

interface SystemConfigurationForm {
  description: string;
  rawValue: string;
}

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.system-configs.page-size';

function readStoredPageSize(): number {
  try {
    const storedValue = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return PAGE_SIZE_OPTIONS.some((pageSize) => pageSize === storedValue) ? storedValue : 10;
  } catch {
    return 10;
  }
}

@Component({
  selector: 'app-system-configs',
  imports: [FormsModule],
  templateUrl: './system-configs.component.html',
  styleUrl: '../configs/configs.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SystemConfigsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;

  private readonly api = inject(SystemConfigurationApiService);

  protected readonly configurations = signal<readonly SystemConfiguration[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(readStoredPageSize());
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);

  protected readonly editorOpen = signal(false);
  protected readonly editingConfiguration = signal<SystemConfiguration | null>(null);
  protected readonly configurationForm = signal<SystemConfigurationForm>({ description: '', rawValue: '' });
  protected readonly changeConfirmed = signal(false);
  protected readonly formError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.loadConfigurations();
  }

  protected async loadConfigurations(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await this.api.listSystemConfigurations(this.pageIndex(), this.pageSize());
      this.configurations.set(result.content);
      this.pageIndex.set(result.page.number);
      this.totalElements.set(result.page.totalElements);
      this.totalPages.set(result.page.totalPages);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected updatePageSize(value: string | number): void {
    const pageSize = Number(value);
    if (!PAGE_SIZE_OPTIONS.some((option) => option === pageSize)) return;

    this.pageSize.set(pageSize);
    this.pageIndex.set(0);
    try {
      localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(pageSize));
    } catch {
      // The selection still applies to this page when browser storage is unavailable.
    }
    void this.loadConfigurations();
  }

  protected goToPage(pageIndex: number): void {
    if (pageIndex < 0 || pageIndex >= this.totalPages() || pageIndex === this.pageIndex()) return;
    this.pageIndex.set(pageIndex);
    void this.loadConfigurations();
  }

  protected openEdit(configuration: SystemConfiguration): void {
    this.editingConfiguration.set(configuration);
    this.configurationForm.set({ description: configuration.description ?? '', rawValue: '' });
    this.changeConfirmed.set(false);
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected patchConfigurationForm(patch: Partial<SystemConfigurationForm>): void {
    this.configurationForm.update((form) => ({ ...form, ...patch }));
    this.formError.set(null);
  }

  protected generateValue(): void {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    const value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
    this.patchConfigurationForm({ rawValue: value });
    this.changeConfirmed.set(false);
  }

  protected async saveConfiguration(): Promise<void> {
    const form = this.configurationForm();
    const editing = this.editingConfiguration();
    this.formError.set(null);
    if (!editing) return;

    if (!form.rawValue) {
      this.formError.set(this.localization.translate('systemConfigs.valueRequired'));
      return;
    }
    if (!this.changeConfirmed()) return;

    this.saving.set(true);
    try {
      await this.api.updateSystemConfiguration(editing.id, {
        version: editing.version,
        description: form.description.trim() || null,
        value: form.rawValue,
      });
      this.editorOpen.set(false);
      await this.loadConfigurations();
    } catch (error) {
      this.formError.set(this.errorMessage(error));
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
