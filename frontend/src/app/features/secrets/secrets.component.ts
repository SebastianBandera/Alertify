import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiRequestError, TagMatchMode } from '../../core/api/configuration-api.service';
import {
  ApplicationSecret,
  DATABASE_ENGINES,
  DatabaseEngine,
  GIT_PROVIDERS,
  GitProvider,
  SECRET_VALUE_TYPES,
  SecretApiService,
  SecretTag,
  SecretValue,
  SecretValueType,
} from '../../core/api/secret-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { ExpressionEditorComponent } from '../../shared/expression-editor/expression-editor.component';

/** Editor state for a DB_SECRET; every field is kept as text and parsed on save, like configs' rawValue. */
interface DatabaseSecretForm {
  engine: DatabaseEngine;
  host: string;
  port: string;
  database: string;
  username: string;
  password: string;
  options: string;
}

/** Editor state for a GIT_SECRET; every field is kept as text and parsed on save, like configs' rawValue. */
interface GitSecretForm {
  provider: GitProvider;
  providerManuallySet: boolean;
  host: string;
  username: string;
  token: string;
  tokenExpiresAt: string;
}

/** Editor state for an OIDC_TOKEN_SET; date-time values are converted to UTC ISO instants on save. */
interface OidcTokenSetForm {
  accessToken: string;
  refreshToken: string;
  idToken: string;
  tokenType: string;
  expiresAt: string;
  refreshExpiresAt: string;
}

interface SecretForm {
  name: string;
  description: string;
  valueType: SecretValueType;
  newValue: string;
  dbValue: DatabaseSecretForm;
  gitValue: GitSecretForm;
  oidcValue: OidcTokenSetForm;
  binaryFile: File | null;
  tagIds: number[];
  writable: boolean;
}

const DEFAULT_PORTS: Readonly<Record<DatabaseEngine, string>> = {
  POSTGRESQL: '5432',
  MARIADB: '3306',
  SQL_SERVER: '1433',
  ORACLE: '1521',
  OTHER: '',
};

/** Hosts whose provider can be inferred automatically; anything else requires a manual choice. */
const GIT_HOSTS_BY_PROVIDER: Readonly<Record<string, GitProvider>> = {
  'github.com': 'GITHUB',
  'www.github.com': 'GITHUB',
  'gitlab.com': 'GITLAB',
  'www.gitlab.com': 'GITLAB',
  'bitbucket.org': 'BITBUCKET',
  'www.bitbucket.org': 'BITBUCKET',
};

interface TagForm {
  name: string;
  color: string;
}

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250, 500, 1000] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.secrets.page-size';
const DEFAULT_MAXIMUM_BINARY_BYTES = 100 * 1024 * 1024;

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
  imports: [FormsModule, ExpressionEditorComponent],
  templateUrl: './secrets.component.html',
  styleUrl: '../configs/configs.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecretsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly valueTypes = SECRET_VALUE_TYPES;
  protected readonly databaseEngines = DATABASE_ENGINES;
  protected readonly gitProviders = GIT_PROVIDERS;
  protected readonly expressionScopes: readonly string[] = ['secrets', 'configs', 'env', 'utils'];
  protected readonly expressionNames = signal<Readonly<Record<string, readonly string[]>>>({ secrets: [], configs: [], env: [], utils: [] });
  protected readonly expressionUtilityFunctions = signal<readonly string[]>([]);
  protected readonly validatingExpression = signal(false);
  protected readonly expressionValid = signal(false);
  private readonly api = inject(SecretApiService);

  protected readonly secrets = signal<readonly ApplicationSecret[]>([]);
  protected readonly tags = signal<readonly SecretTag[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly maximumBinaryBytes = signal(DEFAULT_MAXIMUM_BINARY_BYTES);
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
    await Promise.all([this.loadSecrets(), this.loadTags(), this.loadExpressionSuggestions(), this.loadBinaryLimits()]);
  }

  private async loadBinaryLimits(): Promise<void> {
    try { this.maximumBinaryBytes.set((await this.api.getBinaryLimits()).maximumBytes); }
    catch (error) { this.error.set(this.errorMessage(error)); }
  }

  protected async loadExpressionSuggestions(): Promise<void> {
    try {
      const suggestions = await this.api.getExpressionSuggestions();
      this.expressionNames.set({ secrets: suggestions.secrets, configs: suggestions.configurations, env: suggestions.environmentVariables, utils: suggestions.utilities });
      this.expressionUtilityFunctions.set(suggestions.utilityFunctions);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
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
    this.secretForm.set({
      name: secret.name,
      description: secret.description ?? '',
      valueType: secret.valueType,
      newValue: '',
      dbValue: this.emptyDatabaseForm(),
      gitValue: this.emptyGitForm(),
      oidcValue: this.emptyOidcForm(),
      binaryFile: null,
      tagIds: secret.tags.map((tag) => tag.id),
      writable: secret.writable,
    });
    this.formError.set(null);
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) this.editorOpen.set(false);
  }

  protected patchSecretForm(patch: Partial<SecretForm>): void {
    this.secretForm.update((form) => ({ ...form, ...patch }));
    this.formError.set(null);
    this.expressionValid.set(false);
  }

  protected async validateExpression(): Promise<void> {
    const form = this.secretForm();
    if (this.validatingExpression() || form.valueType !== 'EXPRESSION' || !form.newValue.trim()) return;

    this.validatingExpression.set(true);
    this.expressionValid.set(false);
    this.formError.set(null);
    try {
      const editing = this.editingSecret();
      await this.api.validateExpression({ ...(editing ? { secretId: editing.id } : {}), name: form.name.trim() || undefined, expression: form.newValue });
      this.expressionValid.set(true);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
    } finally {
      this.validatingExpression.set(false);
    }
  }

  protected changeValueType(valueType: SecretValueType): void {
    this.patchSecretForm({ valueType, newValue: '', dbValue: this.emptyDatabaseForm(), gitValue: this.emptyGitForm(), oidcValue: this.emptyOidcForm(), binaryFile: null });
  }

  protected selectBinaryFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.item(0) ?? null;
    if (file && file.size > this.maximumBinaryBytes()) {
      this.patchSecretForm({ binaryFile: null });
      this.formError.set(this.localization.translate('binary.tooLarge').replace('{maximum}', this.formatBytes(this.maximumBinaryBytes())));
      return;
    }
    this.patchSecretForm({ binaryFile: file });
  }

  protected patchDatabaseForm(patch: Partial<DatabaseSecretForm>): void {
    this.secretForm.update((form) => ({ ...form, dbValue: { ...form.dbValue, ...patch } }));
    this.formError.set(null);
  }

  protected changeDatabaseEngine(engine: DatabaseEngine): void {
    this.secretForm.update((form) => {
      const keepPort = form.dbValue.port !== '' && form.dbValue.port !== DEFAULT_PORTS[form.dbValue.engine];
      return { ...form, dbValue: { ...form.dbValue, engine, port: keepPort ? form.dbValue.port : DEFAULT_PORTS[engine] } };
    });
    this.formError.set(null);
  }

  protected patchGitForm(patch: Partial<GitSecretForm>): void {
    this.secretForm.update((form) => ({ ...form, gitValue: { ...form.gitValue, ...patch } }));
    this.formError.set(null);
  }

  /** Infers the provider from well-known hosts unless the user already picked one manually. */
  protected changeGitHost(host: string): void {
    this.secretForm.update((form) => {
      const inferred = GIT_HOSTS_BY_PROVIDER[host.trim().toLowerCase()];
      const provider = !form.gitValue.providerManuallySet && inferred ? inferred : form.gitValue.providerManuallySet ? form.gitValue.provider : 'OTHER';
      return { ...form, gitValue: { ...form.gitValue, host, provider } };
    });
    this.formError.set(null);
  }

  protected changeGitProvider(provider: GitProvider): void {
    this.patchGitForm({ provider, providerManuallySet: true });
  }

  protected patchOidcForm(patch: Partial<OidcTokenSetForm>): void {
    this.secretForm.update((form) => ({ ...form, oidcValue: { ...form.oidcValue, ...patch } }));
    this.formError.set(null);
  }

  protected toggleFormTag(tagId: number, checked: boolean): void {
    this.secretForm.update((form) => ({ ...form, tagIds: checked ? [...form.tagIds, tagId] : form.tagIds.filter((id) => id !== tagId) }));
  }

  protected async saveSecret(): Promise<void> {
    const form = this.secretForm();
    if (form.valueType === 'BINARY') {
      if (!form.name.trim()) { this.formError.set(this.localization.translate('secrets.nameRequired')); return; }
      this.saving.set(true); this.formError.set(null);
      try {
        const editing = this.editingSecret();
        const metadata = { name: form.name.trim(), description: form.description.trim() || null, tagIds: form.tagIds, writable: form.writable };
        if (editing) await this.api.updateBinarySecret(editing.id, { ...metadata, version: editing.version }, form.binaryFile);
        else await this.api.createBinarySecret(metadata, form.binaryFile);
        this.editorOpen.set(false); this.notice.set(this.localization.translate('secrets.saved'));
        await Promise.all([this.loadSecrets(), this.loadExpressionSuggestions()]);
      } catch (error) { this.formError.set(this.errorMessage(error, this.editingSecret() ? 'rename' : undefined)); }
      finally { this.saving.set(false); }
      return;
    }
    let value: SecretValue;
    try {
      if (!form.name.trim()) throw new Error(this.localization.translate('secrets.valueRequired'));
      value = this.parseValue(form);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    try {
      const editing = this.editingSecret();
      if (editing) {
        await this.api.updateSecret(editing.id, { version: editing.version, name: form.name.trim(), description: form.description.trim() || null, valueType: form.valueType, newValue: value, tagIds: form.tagIds, writable: form.writable });
      } else {
        await this.api.createSecret({ name: form.name.trim(), description: form.description.trim() || null, valueType: form.valueType, value, tagIds: form.tagIds, writable: form.writable });
      }
      this.editorOpen.set(false);
      this.notice.set(this.localization.translate('secrets.saved'));
      await Promise.all([this.loadSecrets(), this.loadExpressionSuggestions()]);
    } catch (error) {
      this.formError.set(this.errorMessage(error, this.editingSecret() ? 'rename' : undefined));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteSecret(secret: ApplicationSecret): Promise<void> {
    if (!window.confirm(this.localization.translate('secrets.deleteConfirm'))) return;
    try {
      await this.api.deleteSecret(secret.id, secret.version);
      this.notice.set(this.localization.translate('secrets.deleted'));
      await Promise.all([this.loadSecrets(), this.loadExpressionSuggestions()]);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'delete'));
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

  protected formatBytes(value: number): string { return value < 1024 ? `${value} B` : `${(value / 1024 / 1024).toFixed(2)} MiB`; }

  private parseValue(form: SecretForm): SecretValue {
    switch (form.valueType) {
      case 'DB_SECRET': {
        const db = form.dbValue;
        const host = db.host.trim();
        const database = db.database.trim();
        const username = db.username.trim();
        const options = db.options.trim();
        if (!host || !database || !username || !db.password) throw new Error(this.localization.translate('secrets.value.dbRequired'));
        if (!/^\d+$/.test(db.port.trim()) || Number(db.port) < 1 || Number(db.port) > 65535) throw new Error(this.localization.translate('secrets.value.invalidPort'));
        if (db.engine === 'OTHER' && !options.startsWith('jdbc:')) throw new Error(this.localization.translate('secrets.value.jdbcUrlRequired'));
        return { engine: db.engine, host, port: Number(db.port), database, username, password: db.password, options: options || null };
      }
      case 'GIT_SECRET': {
        const git = form.gitValue;
        const host = git.host.trim();
        const username = git.username.trim();
        const tokenExpiresAt = git.tokenExpiresAt.trim();
        if (!host || !git.token) throw new Error(this.localization.translate('secrets.value.gitRequired'));
        return { provider: git.provider, host, username: username || null, token: git.token, tokenExpiresAt: tokenExpiresAt || null };
      }
      case 'OIDC_TOKEN_SET': {
        const oidc = form.oidcValue;
        const tokenType = oidc.tokenType.trim();
        if (!oidc.accessToken || !tokenType) throw new Error(this.localization.translate('secrets.value.oidcRequired'));
        return {
          accessToken: oidc.accessToken,
          refreshToken: oidc.refreshToken || null,
          idToken: oidc.idToken || null,
          tokenType,
          expiresAt: this.parseOptionalInstant(oidc.expiresAt),
          refreshExpiresAt: this.parseOptionalInstant(oidc.refreshExpiresAt),
        };
      }
      case 'EXPRESSION':
        if (!form.newValue.trim()) throw new Error(this.localization.translate('secrets.value.expressionRequired'));
        return form.newValue;
      default:
        if (!form.newValue) throw new Error(this.localization.translate('secrets.valueRequired'));
        return form.newValue;
    }
  }

  private emptySecretForm(): SecretForm {
    return { name: '', description: '', valueType: 'STRING', newValue: '', dbValue: this.emptyDatabaseForm(), gitValue: this.emptyGitForm(), oidcValue: this.emptyOidcForm(), binaryFile: null, tagIds: [], writable: false };
  }

  private emptyDatabaseForm(): DatabaseSecretForm {
    return { engine: 'POSTGRESQL', host: '', port: DEFAULT_PORTS.POSTGRESQL, database: '', username: '', password: '', options: '' };
  }

  private emptyGitForm(): GitSecretForm {
    return { provider: 'OTHER', providerManuallySet: false, host: '', username: '', token: '', tokenExpiresAt: '' };
  }

  private emptyOidcForm(): OidcTokenSetForm {
    return { accessToken: '', refreshToken: '', idToken: '', tokenType: 'Bearer', expiresAt: '', refreshExpiresAt: '' };
  }

  private parseOptionalInstant(value: string): string | null {
    if (!value)
      return null;

    const instant = new Date(value);
    if (Number.isNaN(instant.getTime()))
      throw new Error(this.localization.translate('secrets.value.invalidInstant'));

    return instant.toISOString();
  }

  private errorMessage(error: unknown, referencedOperation?: 'delete' | 'rename'): string {
    if (error instanceof ApiRequestError && error.code === 'SECRET_REFERENCED_BY_EXPRESSION') {
      const name = error.parameters['secretName'] ?? '';
      const marker = 'because it is referenced by:';
      const markerIndex = error.message.lastIndexOf(marker);
      const dependents = markerIndex < 0
        ? this.localization.translate('secrets.expression.referencedUnknown')
        : error.message.slice(markerIndex + marker.length).trim().replace(/\.$/, '');
      const key = referencedOperation === 'rename' ? 'secrets.expression.referencedRename' : 'secrets.expression.referencedDelete';
      return this.localization.translate(key).replace('{name}', name).replace('{dependents}', dependents);
    }
    return error instanceof Error ? error.message : String(error);
  }
}
