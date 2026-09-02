import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  ApiRequestError,
  ApplicationConfiguration,
  ConfigurationApiService,
  ConfigurationImportResult,
  ConfigurationTag,
  ConfigurationValueType,
  TagMatchMode,
} from '../../core/api/configuration-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';

interface ConfigurationForm {
  name: string;
  description: string;
  valueType: ConfigurationValueType;
  rawValue: string;
  tagIds: number[];
  writable: boolean;
}

interface TagForm {
  name: string;
  color: string;
}

interface ExpressionCompletion {
  readonly label: string;
  readonly replacement: string;
  readonly replacementStart: number;
  readonly replacementEnd: number;
}

const VALUE_TYPES: readonly ConfigurationValueType[] = [
  'STRING',
  'INTEGER',
  'DECIMAL',
  'BOOLEAN',
  'DATE',
  'TIME',
  'DATE_TIME',
  'JSON',
  'EXPRESSION',
];

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250, 500, 1000] as const;
const PAGE_SIZE_STORAGE_KEY = 'alertify.configs.page-size';

function readStoredPageSize(): number {
  try {
    const storedValue = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return PAGE_SIZE_OPTIONS.some((pageSize) => pageSize === storedValue) ? storedValue : 10;
  } catch {
    return 10;
  }
}

@Component({
  selector: 'app-configs',
  imports: [FormsModule],
  templateUrl: './configs.component.html',
  styleUrl: './configs.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigsComponent implements OnInit {
  protected readonly localization = inject(LocalizationService);
  protected readonly valueTypes = VALUE_TYPES;
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;

  private readonly api = inject(ConfigurationApiService);

  protected readonly configurations = signal<readonly ApplicationConfiguration[]>([]);
  protected readonly tags = signal<readonly ConfigurationTag[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly exporting = signal(false);
  protected readonly importing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly appliedSearchTerm = signal('');
  protected readonly valueSearchTerm = signal('');
  protected readonly appliedValueSearchTerm = signal('');
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
  protected readonly editingConfiguration = signal<ApplicationConfiguration | null>(null);
  protected readonly configurationForm = signal<ConfigurationForm>(this.emptyConfigurationForm());
  protected readonly sensitiveChangeConfirmed = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly evaluatingExpression = signal(false);
  protected readonly evaluatedExpression = signal<string | null>(null);
  protected readonly expressionConfigurationNames = signal<readonly string[]>([]);
  protected readonly expressionEnvironmentNames = signal<readonly string[]>([]);
  protected readonly expressionUtilityNames = signal<readonly string[]>([]);
  protected readonly expressionCompletions = signal<readonly ExpressionCompletion[]>([]);
  protected readonly selectedExpressionCompletion = signal(0);

  protected readonly tagDialogOpen = signal(false);
  protected readonly editingTag = signal<ConfigurationTag | null>(null);
  protected readonly tagForm = signal<TagForm>({ name: '', color: '#6D5DFC' });
  protected readonly tagError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await Promise.all([this.loadConfigurations(), this.loadTags(), this.loadExpressionSuggestions()]);
  }

  protected async loadConfigurations(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await this.api.listConfigurations(
        this.appliedSearchTerm(),
        this.appliedValueSearchTerm(),
        this.selectedTagIds(),
        this.tagMatchMode(),
        this.pageIndex(),
        this.pageSize(),
      );
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

  protected async loadTags(): Promise<void> {
    try {
      this.tags.set(await this.api.listTags());
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected async loadExpressionSuggestions(): Promise<void> {
    try {
      const suggestions = await this.api.getExpressionSuggestions();
      this.expressionConfigurationNames.set(suggestions.configurations);
      this.expressionEnvironmentNames.set(suggestions.environmentVariables);
      this.expressionUtilityNames.set(suggestions.utilities);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    }
  }

  protected async exportConfigurations(): Promise<void> {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const blob = await this.api.exportConfigurations();
      const url = URL.createObjectURL(blob);
      try {
        const link = document.createElement('a');
        link.href = url;
        link.download = 'alertify-configurations.csv';
        document.body.appendChild(link);
        link.click();
        link.remove();
      } finally {
        URL.revokeObjectURL(url);
      }
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.exporting.set(false);
    }
  }

  protected chooseImportFile(fileInput: HTMLInputElement): void {
    if (this.importing()) return;
    fileInput.value = '';
    fileInput.click();
  }

  protected async importConfigurations(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0);
    if (!file) return;
    if (!window.confirm(this.localization.translate('configs.importConfirm'))) {
      input.value = '';
      return;
    }

    this.importing.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const result = await this.api.importConfigurations(file);
      this.notice.set(this.importSuccessMessage(result));
      this.pageIndex.set(0);
      await Promise.all([
        this.loadConfigurations(),
        this.loadTags(),
        this.loadExpressionSuggestions(),
      ]);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      input.value = '';
      this.importing.set(false);
    }
  }

  protected updateSearch(value: string): void {
    this.searchTerm.set(value);
  }

  protected updateValueSearch(value: string): void {
    this.valueSearchTerm.set(value);
  }

  protected applySearch(): void {
    this.appliedSearchTerm.set(this.searchTerm());
    this.appliedValueSearchTerm.set(this.valueSearchTerm());
    this.pageIndex.set(0);
    void this.loadConfigurations();
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

  protected addTagFilter(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const value = select.value;
    select.value = '';
    if (!value) return;

    const tagId = Number(value);
    if (
      !Number.isSafeInteger(tagId) ||
      this.selectedTagIds().includes(tagId) ||
      !this.tags().some((tag) => tag.id === tagId)
    ) {
      return;
    }

    this.selectedTagIds.update((tagIds) => [...tagIds, tagId]);
    this.pageIndex.set(0);
    void this.loadConfigurations();
  }

  protected removeTagFilter(tagId: number): void {
    this.selectedTagIds.update((tagIds) => tagIds.filter((currentId) => currentId !== tagId));
    this.pageIndex.set(0);
    void this.loadConfigurations();
  }

  protected updateTagMatchMode(mode: TagMatchMode): void {
    if (this.tagMatchMode() === mode) return;
    this.tagMatchMode.set(mode);
    if (this.selectedTagIds().length >= 2) {
      this.pageIndex.set(0);
      void this.loadConfigurations();
    }
  }

  protected openCreate(): void {
    this.editingConfiguration.set(null);
    this.configurationForm.set(this.emptyConfigurationForm());
    this.sensitiveChangeConfirmed.set(false);
    this.formError.set(null);
    this.resetExpressionEditorState();
    this.editorOpen.set(true);
  }

  protected openEdit(configuration: ApplicationConfiguration): void {
    this.editingConfiguration.set(configuration);
    this.configurationForm.set({
      name: configuration.name,
      description: configuration.description ?? '',
      valueType: configuration.valueType,
      rawValue: configuration.valueHidden
        ? ''
        : this.toEditorValue(configuration.valueType, configuration.value),
      tagIds: configuration.tags.map((tag) => tag.id),
      writable: configuration.writable,
    });
    this.sensitiveChangeConfirmed.set(false);
    this.formError.set(null);
    this.resetExpressionEditorState();
    this.editorOpen.set(true);
  }

  protected closeEditor(): void {
    if (!this.saving()) {
      this.editorOpen.set(false);
      this.resetExpressionEditorState();
    }
  }

  protected patchConfigurationForm(patch: Partial<ConfigurationForm>): void {
    this.configurationForm.update((form) => ({ ...form, ...patch }));
    this.formError.set(null);
    if (patch.rawValue !== undefined || patch.name !== undefined || patch.valueType !== undefined) {
      this.evaluatedExpression.set(null);
    }
  }

  protected updateExpressionValue(event: Event): void {
    const textarea = event.target as HTMLTextAreaElement;
    this.patchConfigurationForm({ rawValue: textarea.value });
    this.refreshExpressionCompletions(textarea);
  }

  protected refreshExpressionCompletions(textarea: HTMLTextAreaElement): void {
    if (this.configurationForm().valueType !== 'EXPRESSION') {
      this.closeExpressionCompletions();
      return;
    }

    const value = textarea.value;
    const cursor = textarea.selectionStart ?? value.length;
    const opening = value.lastIndexOf('{{', cursor - 1);
    const lastClosing = value.lastIndexOf('}}', cursor - 1);
    if (opening < 0 || opening < lastClosing) {
      this.closeExpressionCompletions();
      return;
    }

    const fragment = value.slice(opening + 2, cursor);
    const separator = fragment.indexOf('.');
    const scope = separator < 0 ? '' : fragment.slice(0, separator).toLowerCase();
    const allowsSpaces = scope === 'configs';
    if (/[{}]/.test(fragment) || (!allowsSpaces && /\s/.test(fragment))) {
      this.closeExpressionCompletions();
      return;
    }

    const completions = this.buildExpressionCompletions(fragment, opening + 2, cursor, value.slice(cursor));
    this.expressionCompletions.set(completions);
    this.selectedExpressionCompletion.set(0);
  }

  protected handleExpressionKeydown(event: KeyboardEvent, textarea: HTMLTextAreaElement): void {
    const completions = this.expressionCompletions();
    if (completions.length === 0) return;

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedExpressionCompletion.update((index) => (index + 1) % completions.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedExpressionCompletion.update((index) => (index - 1 + completions.length) % completions.length);
    } else if (event.key === 'Tab' || event.key === 'Enter') {
      event.preventDefault();
      this.applyExpressionCompletion(textarea, this.selectedExpressionCompletion());
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.closeExpressionCompletions();
    }
  }

  protected chooseExpressionCompletion(textarea: HTMLTextAreaElement, index: number, event: MouseEvent): void {
    event.preventDefault();
    this.applyExpressionCompletion(textarea, index);
  }

  protected closeExpressionCompletions(): void {
    this.expressionCompletions.set([]);
    this.selectedExpressionCompletion.set(0);
  }

  protected async evaluateExpression(): Promise<void> {
    const form = this.configurationForm();
    if (this.evaluatingExpression() || form.valueType !== 'EXPRESSION') return;

    this.evaluatingExpression.set(true);
    this.evaluatedExpression.set(null);
    this.formError.set(null);
    this.closeExpressionCompletions();
    try {
      const response = await this.api.evaluateExpression({
        ...(this.editingConfiguration() ? { configurationId: this.editingConfiguration()!.id } : {}),
        configurationName: form.name.trim(),
        expression: form.rawValue,
      });
      this.evaluatedExpression.set(response.value);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
    } finally {
      this.evaluatingExpression.set(false);
    }
  }

  protected toggleConfigurationTag(tagId: number, checked: boolean): void {
    this.configurationForm.update((form) => ({
      ...form,
      tagIds: checked
        ? [...form.tagIds, tagId]
        : form.tagIds.filter((currentId) => currentId !== tagId),
    }));
  }

  protected hasSelectedTag(tagId: number): boolean {
    return this.configurationForm().tagIds.includes(tagId);
  }

  protected generateKeyPart(): void {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    const value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
    this.patchConfigurationForm({ rawValue: value });
    this.sensitiveChangeConfirmed.set(false);
  }

  protected async saveConfiguration(): Promise<void> {
    const form = this.configurationForm();
    const editing = this.editingConfiguration();
    this.formError.set(null);

    if (!form.name.trim()) return;
    if (editing?.valueHidden && !form.rawValue) {
      this.formError.set(this.localization.translate('configs.keyPart.valueRequired'));
      return;
    }
    if (editing?.changeWarning === 'SECRET_LOSS' && !this.sensitiveChangeConfirmed()) return;

    let value: unknown;
    try {
      value = this.parseValue(form.valueType, form.rawValue);
    } catch (error) {
      this.formError.set(this.errorMessage(error));
      return;
    }

    this.saving.set(true);
    try {
      const request = {
        ...(editing ? { version: editing.version } : {}),
        name: form.name.trim(),
        description: form.description.trim() || null,
        valueType: form.valueType,
        value,
        tagIds: form.tagIds,
        writable: form.writable,
      };
      if (editing) {
        await this.api.updateConfiguration(editing.id, request);
      } else {
        await this.api.createConfiguration(request);
      }
      this.resetExpressionEditorState();
      this.editorOpen.set(false);
      await Promise.all([
        this.loadConfigurations(),
        this.loadExpressionSuggestions(),
      ]);
    } catch (error) {
      this.formError.set(this.errorMessage(error, 'rename'));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteConfiguration(configuration: ApplicationConfiguration): Promise<void> {
    if (!configuration.deletable) return;
    if (!window.confirm(this.localization.translate('configs.deleteConfirm'))) return;

    this.error.set(null);
    try {
      await this.api.deleteConfiguration(configuration.id, configuration.version);
      if (this.configurations().length === 1 && this.pageIndex() > 0) {
        this.pageIndex.update((pageIndex) => pageIndex - 1);
      }
      await Promise.all([
        this.loadConfigurations(),
        this.loadExpressionSuggestions(),
      ]);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'delete'));
    }
  }

  protected valuePreview(configuration: ApplicationConfiguration): string {
    if (configuration.valueHidden) return '••••••••••••••••';
    if (configuration.valueType === 'EXPRESSION') {
      return this.localization.translate('configs.expression.onDemand');
    }
    if (configuration.valueType === 'JSON') return JSON.stringify(configuration.value);
    return String(configuration.value);
  }

  protected formatDate(value: string): string {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return value;
    return new Intl.DateTimeFormat(this.localization.locale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(parsed);
  }

  protected openTagManager(): void {
    this.resetTagForm();
    this.tagDialogOpen.set(true);
  }

  protected closeTagManager(): void {
    if (!this.saving()) this.tagDialogOpen.set(false);
  }

  protected editTag(tag: ConfigurationTag): void {
    this.editingTag.set(tag);
    this.tagForm.set({ name: tag.name, color: tag.color });
    this.tagError.set(null);
  }

  protected resetTagForm(): void {
    this.editingTag.set(null);
    this.tagForm.set({ name: '', color: '#6D5DFC' });
    this.tagError.set(null);
  }

  protected patchTagForm(patch: Partial<TagForm>): void {
    this.tagForm.update((form) => ({ ...form, ...patch }));
    this.tagError.set(null);
  }

  protected async saveTag(): Promise<void> {
    const form = this.tagForm();
    const editing = this.editingTag();
    if (!form.name.trim()) return;

    this.saving.set(true);
    this.tagError.set(null);
    try {
      if (editing) {
        await this.api.updateTag(editing.id, {
          version: editing.version,
          name: form.name.trim(),
          color: form.color,
        });
      } else {
        await this.api.createTag({ name: form.name.trim(), color: form.color });
      }
      await this.loadTags();
      this.resetTagForm();
    } catch (error) {
      this.tagError.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected async deleteTag(tag: ConfigurationTag): Promise<void> {
    if (!window.confirm(this.localization.translate('configs.tags.deleteConfirm'))) return;
    this.tagError.set(null);
    try {
      await this.api.deleteTag(tag.id, tag.version);
      this.selectedTagIds.update((tagIds) => tagIds.filter((tagId) => tagId !== tag.id));
      this.pageIndex.set(0);
      await Promise.all([this.loadTags(), this.loadConfigurations()]);
      if (this.editingTag()?.id === tag.id) this.resetTagForm();
    } catch (error) {
      this.tagError.set(this.errorMessage(error));
    }
  }

  private parseValue(type: ConfigurationValueType, rawValue: string): unknown {
    switch (type) {
      case 'INTEGER': {
        if (!/^-?\d+$/.test(rawValue.trim())) {
          throw new Error(this.localization.translate('configs.value.invalidInteger'));
        }
        const value = Number(rawValue);
        if (!Number.isSafeInteger(value)) {
          throw new Error(this.localization.translate('configs.value.invalidInteger'));
        }
        return value;
      }
      case 'DECIMAL': {
        const value = Number(rawValue);
        if (!rawValue.trim() || !Number.isFinite(value)) {
          throw new Error(this.localization.translate('configs.value.invalidDecimal'));
        }
        return value;
      }
      case 'BOOLEAN':
        return rawValue === 'true';
      case 'DATE_TIME': {
        const value = new Date(rawValue);
        if (Number.isNaN(value.getTime())) {
          throw new Error(this.localization.translate('configs.value.invalidJson'));
        }
        return value.toISOString();
      }
      case 'TIME':
        if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(rawValue)) {
          throw new Error(this.localization.translate('configs.value.invalidTime'));
        }
        return rawValue;
      case 'JSON':
        try {
          const value: unknown = JSON.parse(rawValue);
          if (typeof value !== 'object' || value === null) throw new Error();
          return value;
        } catch {
          throw new Error(this.localization.translate('configs.value.invalidJson'));
        }
      default:
        return rawValue;
    }
  }

  private buildExpressionCompletions(
    fragment: string,
    replacementStart: number,
    replacementEnd: number,
    remainingValue: string,
  ): readonly ExpressionCompletion[] {
    const normalizedFragment = fragment.toLowerCase();
    if (!fragment.includes('.')) {
      return ['configs', 'env', 'utils']
        .filter((scope) => scope.startsWith(normalizedFragment))
        .map((scope) => ({
          label: scope,
          replacement: `${scope}.`,
          replacementStart,
          replacementEnd,
        }));
    }

    const separator = fragment.indexOf('.');
    const scope = fragment.slice(0, separator).toLowerCase();
    const prefix = fragment.slice(separator + 1);
    const names = scope === 'configs'
      ? this.expressionConfigurationNames()
      : scope === 'env'
        ? this.expressionEnvironmentNames()
        : scope === 'utils'
          ? this.expressionUtilityNames()
          : [];
    const closing = remainingValue.startsWith('}}') ? '' : '}}';
    return names
      .filter((name) => name.toLowerCase().startsWith(prefix.toLowerCase()))
      .slice(0, 50)
      .map((name) => ({
        label: `${scope}.${name}`,
        replacement: `${scope}.${name}${closing}`,
        replacementStart,
        replacementEnd,
      }));
  }

  private applyExpressionCompletion(textarea: HTMLTextAreaElement, index: number): void {
    const completion = this.expressionCompletions()[index];
    if (!completion) return;

    const currentValue = textarea.value;
    const value = currentValue.slice(0, completion.replacementStart)
      + completion.replacement
      + currentValue.slice(completion.replacementEnd);
    const cursor = completion.replacementStart + completion.replacement.length;
    textarea.value = value;
    this.patchConfigurationForm({ rawValue: value });
    this.closeExpressionCompletions();
    queueMicrotask(() => {
      textarea.focus();
      textarea.setSelectionRange(cursor, cursor);
      if (completion.replacement.endsWith('.')) this.refreshExpressionCompletions(textarea);
    });
  }

  private resetExpressionEditorState(): void {
    this.evaluatingExpression.set(false);
    this.evaluatedExpression.set(null);
    this.closeExpressionCompletions();
  }

  private toEditorValue(type: ConfigurationValueType, value: unknown): string {
    if (type === 'JSON') return JSON.stringify(value, null, 2);
    if (type === 'DATE_TIME') {
      const date = new Date(String(value));
      const localTime = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
      return localTime.toISOString().slice(0, 16);
    }
    return String(value);
  }

  private emptyConfigurationForm(): ConfigurationForm {
    return {
      name: '',
      description: '',
      valueType: 'STRING',
      rawValue: '',
      tagIds: [],
      writable: false,
    };
  }

  private importSuccessMessage(result: ConfigurationImportResult): string {
    return this.localization.translate('configs.importSuccess')
      .replace('{created}', String(result.created))
      .replace('{updated}', String(result.updated))
      .replace('{unchanged}', String(result.unchanged))
      .replace('{tagsCreated}', String(result.tagsCreated));
  }

  private errorMessage(error: unknown, referencedOperation?: 'delete' | 'rename'): string {
    if (error instanceof ApiRequestError && error.code === 'CONFIGURATION_REFERENCED_BY_EXPRESSION') {
      const name = error.parameters['configurationName'] ?? '';
      const dependents = this.referencedConfigurationNames(error)
        ?? this.localization.translate('configs.expression.referencedUnknown');
      const key = referencedOperation === 'rename'
        ? 'configs.expression.referencedRename'
        : 'configs.expression.referencedDelete';
      return this.localization.translate(key)
        .replace('{name}', name)
        .replace('{dependents}', dependents);
    }
    if (error instanceof ApiRequestError && error.code === 'CONFIGURATION_TAG_IN_USE') {
      const tagName = error.parameters['tagName'] ?? '';
      return this.localization.translate('configs.tags.inUse')
        .replace('{name}', tagName);
    }
    return error instanceof Error ? error.message : String(error);
  }

  private referencedConfigurationNames(error: ApiRequestError): string | null {
    const marker = 'because it is referenced by:';
    const markerIndex = error.message.lastIndexOf(marker);
    if (markerIndex < 0) return null;

    const names = error.message.slice(markerIndex + marker.length).trim();
    return names.replace(/\.$/, '') || null;
  }
}
