import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApplicationLog,
  ApplicationLogFilters,
  ApplicationLogLevel,
  ApplicationLogOutcome,
  LogApiService,
} from '../../core/api/log-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { TranslationKey } from '../../core/i18n/localization.types';

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250, 500, 1000] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.logs.page-size';
const EVENT_TRANSLATION_KEYS = {
  API_ERROR_SHOWN: 'logs.event.API_ERROR_SHOWN',
  API_REQUEST: 'logs.event.API_REQUEST',
  API_UNHANDLED_ERROR: 'logs.event.API_UNHANDLED_ERROR',
  CONFIGURATION_CREATED: 'logs.event.CONFIGURATION_CREATED',
  CONFIGURATION_DELETED: 'logs.event.CONFIGURATION_DELETED',
  CONFIGURATION_PAGE_VIEWED: 'logs.event.CONFIGURATION_PAGE_VIEWED',
  CONFIGURATION_TAG_CREATED: 'logs.event.CONFIGURATION_TAG_CREATED',
  CONFIGURATION_TAG_DELETED: 'logs.event.CONFIGURATION_TAG_DELETED',
  CONFIGURATION_TAG_UPDATED: 'logs.event.CONFIGURATION_TAG_UPDATED',
  CONFIGURATION_UPDATED: 'logs.event.CONFIGURATION_UPDATED',
  CONFIGURATION_VIEWED: 'logs.event.CONFIGURATION_VIEWED',
  USER_LOGIN: 'logs.event.USER_LOGIN',
  USER_LOGOUT: 'logs.event.USER_LOGOUT',
} as const satisfies Readonly<Record<string, TranslationKey>>;

function startOfToday(): string {
  const now = new Date();
  const year = String(now.getFullYear());
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}T00:00`;
}

function defaultFilters(): ApplicationLogFilters {
  return {
    username: '',
    subject: '',
    event: '',
    path: '',
    level: '',
    outcome: '',
    from: startOfToday(),
    to: '',
  };
}

function readStoredPageSize(): number {
  try {
    const stored = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return PAGE_SIZE_OPTIONS.some((option) => option === stored) ? stored : 25;
  } catch {
    return 25;
  }
}

@Component({
  selector: 'app-logs',
  imports: [DatePipe, FormsModule],
  templateUrl: './logs.component.html',
  styleUrl: './logs.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly levels: readonly ApplicationLogLevel[] = ['INFO', 'WARN', 'ERROR'];
  protected readonly outcomes: readonly ApplicationLogOutcome[] = ['SUCCESS', 'FAILURE'];
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly api = inject(LogApiService);
  protected readonly logs = signal<readonly ApplicationLog[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly filters = signal<ApplicationLogFilters>(defaultFilters());
  protected readonly appliedFilters = signal<ApplicationLogFilters>(defaultFilters());
  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(readStoredPageSize());
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected patchFilters(patch: Partial<ApplicationLogFilters>): void {
    this.filters.update((current) => ({ ...current, ...patch }));
  }

  protected applyFilters(): void {
    this.appliedFilters.set({ ...this.filters() });
    this.pageIndex.set(0);
    void this.load();
  }

  protected clearFilters(): void {
    const filters = defaultFilters();
    this.filters.set(filters);
    this.appliedFilters.set(filters);
    this.pageIndex.set(0);
    void this.load();
  }

  protected updatePageSize(value: string | number): void {
    const size = Number(value);
    if (!PAGE_SIZE_OPTIONS.some((option) => option === size)) return;

    this.pageSize.set(size);
    this.pageIndex.set(0);
    try {
      localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(size));
    } catch {
      // Keep the in-memory selection when browser storage is unavailable.
    }
    void this.load();
  }

  protected goToPage(index: number): void {
    if (index < 0 || index >= this.totalPages() || index === this.pageIndex()) return;

    this.pageIndex.set(index);
    void this.load();
  }

  protected formatData(data: Readonly<Record<string, unknown>>): string {
    return JSON.stringify(data, null, 2);
  }

  protected eventMessage(event: string): string {
    const key = EVENT_TRANSLATION_KEYS[event as keyof typeof EVENT_TRANSLATION_KEYS];
    return key ? this.localization.translate(key) : event;
  }

  protected copyRequestId(requestId: string): void {
    if (navigator.clipboard?.writeText) {
      void navigator.clipboard
        .writeText(requestId)
        .catch(() => this.copyTextFallback(requestId));
      return;
    }
    this.copyTextFallback(requestId);
  }

  protected requestIdCopyLabel(requestId: string): string {
    return `${this.localization.translate('logs.requestId.copy')}: ${requestId}`;
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await this.api.list(
        this.appliedFilters(),
        this.pageIndex(),
        this.pageSize(),
      );
      this.logs.set(result.content);
      this.pageIndex.set(result.page.number);
      this.totalElements.set(result.page.totalElements);
      this.totalPages.set(result.page.totalPages);
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.loading.set(false);
    }
  }

  private copyTextFallback(value: string): void {
    const input = document.createElement('textarea');
    input.value = value;
    input.setAttribute('readonly', '');
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    input.select();
    document.execCommand('copy');
    input.remove();
  }
}
