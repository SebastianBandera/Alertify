import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiRequestError, TagMatchMode } from '../../core/api/configuration-api.service';
import { ApplicationSecret, SecretApiService, SecretTag } from '../../core/api/secret-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

interface SecretForm {
  name: string;
  description: string;
  newValue: string;
  tagIds: number[];
}

interface TagForm {
  name: string;
  color: string;
}

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250, 500, 1000] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.secrets.page-size';

function readStoredPageSize(): number {
  try {
    const storedValue = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return PAGE_SIZE_OPTIONS.some((pageSize) => pageSize === storedValue) ? storedValue : 10;
  } catch {
    return 10;
  }
}

@Component({
  selector: 'app-secrets',
  imports: [FormsModule],
  templateUrl: './secrets.component.html',
  styleUrl: '../configs/configs.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecretsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly api = inject(SecretApiService);

  protected readonly secrets = signal<readonly ApplicationSecret[]>([]);
  protected readonly tags = signal<readonly SecretTag[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly appliedSearchTerm = signal('');
  protected readonly selectedTagIds = signal<readonly number[]>([]);
  protected readonly tagMatchMode = signal<TagMatchMode>('OR');
  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(readStoredPageSize());
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly selectedFilterTags = computed(() => {
    const tagsById = new Map(this.tags().map((tag) => [tag.id, tag]));
    return this.selectedTagIds().flatMap((tagId) => {
      const tag = tagsById.get(tagId);
      return tag ? [tag] : [];
    });
  });
  protected readonly availableFilterTags = computed(() => {
    const selectedIds = new Set(this.selectedTagIds());
    return this.tags().filter((tag) => !selectedIds.has(tag.id));
  });

  protected readonly editorOpen = signal(false);
  protected readonly editingSecret = signal<ApplicationSecret | null>(null);
  protected readonly secretForm = signal<SecretForm>(this.emptySecretForm());
  protected readonly formError = signal<string | null>(null);
  protected readonly tagDialogOpen = signal(false);
  protected readonly editingTag = signal<SecretTag | null>(null);
  protected readonly tagForm = signal<TagForm>({ name: '', color: '#6D5DFC' });
  protected readonly tagError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await Promise.all([this.loadSecrets(), this.loadTags()]);
  }

  protected async loadSecrets(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await this.api.listSecrets(this.appliedSearchTerm(), this.selectedTagIds(), this.tagMatchMode(), this.pageIndex(), this.pageSize());
      this.secrets.set(result.content);
      this.pageIndex.set(result.page.number);
      this.totalElements.set(result.page.totalElements);
      this.totalPages.set(result.page.totalPages);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadTags(): Promise<void> {
    try {
      this.tags.set(await this.api.listTags());
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected applySearch(): void {
    this.appliedSearchTerm.set(this.searchTerm().trim());
    this.pageIndex.set(0);
    void this.loadSecrets();
  }

  protected addTagFilter(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const tagId = Number(select.value);
    if (tagId && !this.selectedTagIds().includes(tagId)) {
      this.selectedTagIds.set([...this.selectedTagIds(), tagId]);
      this.pageIndex.set(0);
      void this.loadSecrets();
    }
    select.value = '';
  }

  protected removeTagFilter(tagId: number): void {
    this.selectedTagIds.set(this.selectedTagIds().filter((id) => id !== tagId));
    this.pageIndex.set(0);
    void this.loadSecrets();
  }

  protected updateTagMatchMode(mode: TagMatchMode): void {
    this.tagMatchMode.set(mode);
    this.pageIndex.set(0);
    void this.loadSecrets();
  }

  protected updatePageSize(value: number | string): void {
    const size = Number(value);
    this.pageSize.set(size);
    localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(size));
    this.pageIndex.set(0);
    void this.loadSecrets();
  }

  protected goToPage(page: number): void {
    this.pageIndex.set(page);
    void this.loadSecrets();
  }

  protected openCreate(): void {
    this.editingSecret.set(null);
    this.secretForm.set(this.emptySecretForm());
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected openEdit(secret: ApplicationSecret): void {
    this.editingSecret.set(secret);
    this.secretForm.set({ name: secret.name, description: secret.description ?? '', newValue: '', tagIds: secret.tags.map((tag) => tag.id) });
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected updateSecretForm(field: 'name' | 'description' | 'newValue', value: string): void {
    this.secretForm.update((form) => ({ ...form, [field]: value }));
  }

  protected toggleFormTag(tagId: number, checked: boolean): void {
    this.secretForm.update((form) => ({ ...form, tagIds: checked ? [...form.tagIds, tagId] : form.tagIds.filter((id) => id !== tagId) }));
  }

  protected async saveSecret(): Promise<void> {
    const form = this.secretForm();
    if (!form.name.trim() || !form.newValue) {
      this.formError.set(this.localization.translate('secrets.valueRequired'));
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    try {
      const editing = this.editingSecret();
      if (editing) {
        await this.api.updateSecret(editing.id, { version: editing.version, name: form.name.trim(), description: form.description.trim() || null, newValue: form.newValue, tagIds: form.tagIds });
      } else {
        await this.api.createSecret({ name: form.name.trim(), description: form.description.trim() || null, value: form.newValue, tagIds: form.tagIds });
      }
      this.editorOpen.set(false);
      this.notice.set(this.localization.translate('secrets.saved'));
      await this.loadSecrets();
    } catch (error) {
      this.formError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteSecret(secret: ApplicationSecret): Promise<void> {
    if (!window.confirm(this.localization.translate('secrets.deleteConfirm'))) return;
    try {
      await this.api.deleteSecret(secret.id, secret.version);
      this.notice.set(this.localization.translate('secrets.deleted'));
      await this.loadSecrets();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected openTagManager(): void {
    this.editingTag.set(null);
    this.tagForm.set({ name: '', color: '#6D5DFC' });
    this.tagError.set(null);
    this.tagDialogOpen.set(true);
  }

  protected closeTagManager(): void {
    if (!this.saving()) this.tagDialogOpen.set(false);
  }

  protected editTag(tag: SecretTag): void {
    this.editingTag.set(tag);
    this.tagForm.set({ name: tag.name, color: tag.color });
  }

  protected updateTagForm(field: keyof TagForm, value: string): void {
    this.tagForm.update((form) => ({ ...form, [field]: value }));
  }

  protected async saveTag(): Promise<void> {
    const form = this.tagForm();
    if (!form.name.trim()) return;
    this.saving.set(true);
    this.tagError.set(null);
    try {
      const editing = this.editingTag();
      if (editing) await this.api.updateTag(editing.id, { version: editing.version, name: form.name.trim(), color: form.color });
      else await this.api.createTag({ name: form.name.trim(), color: form.color });
      this.editingTag.set(null);
      this.tagForm.set({ name: '', color: '#6D5DFC' });
      await this.loadTags();
      await this.loadSecrets();
    } catch (error) {
      this.tagError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteTag(tag: SecretTag): Promise<void> {
    if (!window.confirm(this.localization.translate('secrets.tags.deleteConfirm'))) return;
    try {
      await this.api.deleteTag(tag.id, tag.version);
      await this.loadTags();
    } catch (error) {
      this.tagError.set(error instanceof ApiRequestError && error.code === 'SECRET_TAG_IN_USE' ? this.localization.translate('secrets.tags.inUse') : this.errorMessage(error));
    }
  }

  protected formatDate(value: string): string {
    return new Intl.DateTimeFormat(this.localization.locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
  }

  private emptySecretForm(): SecretForm {
    return { name: '', description: '', newValue: '', tagIds: [] };
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
  }
}
