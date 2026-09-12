import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

export interface ExpressionEditorNames {
  readonly [scope: string]: readonly string[];
}

interface ExpressionCompletion {
  readonly label: string;
  readonly replacement: string;
  readonly replacementStart: number;
  readonly replacementEnd: number;
}

/**
 * Textarea for `{{scope.NAME}}` expressions with inline autocompletion of the
 * scopes and names it receives. Utility functions complete as `utils.NAME(`
 * so the argument (which may contain further references) can be typed next.
 */
@Component({
  selector: 'app-expression-editor',
  templateUrl: './expression-editor.component.html',
  styleUrl: './expression-editor.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExpressionEditorComponent {
  readonly value = input.required<string>();
  readonly name = input.required<string>();
  readonly placeholder = input('');
  readonly required = input(true);
  readonly rows = input(6);
  /** Scopes offered before the dot, e.g. ['configs', 'env', 'utils'] or with 'secrets'. */
  readonly scopes = input.required<readonly string[]>();
  /** Names available per scope, keyed by scope. */
  readonly names = input.required<ExpressionEditorNames>();
  /** Utility functions that take an argument; completed as `utils.NAME(`. */
  readonly utilityFunctions = input<readonly string[]>([]);
  readonly valueChange = output<string>();

  protected readonly completions = signal<readonly ExpressionCompletion[]>([]);
  protected readonly selectedCompletion = signal(0);
  protected readonly listId = `expression-completions-${Math.random().toString(36).slice(2, 9)}`;

  protected updateValue(event: Event): void {
    const textarea = event.target as HTMLTextAreaElement;
    this.valueChange.emit(textarea.value);
    this.refreshCompletions(textarea);
  }

  protected refreshCompletions(textarea: HTMLTextAreaElement): void {
    const value = textarea.value;
    const cursor = textarea.selectionStart ?? value.length;
    const opening = value.lastIndexOf('{{', cursor - 1);
    const lastClosing = value.lastIndexOf('}}', cursor - 1);
    if (opening < 0 || opening < lastClosing) {
      this.closeCompletions();
      return;
    }

    const fragment = value.slice(opening + 2, cursor);
    const separator = fragment.indexOf('.');
    const scope = separator < 0 ? '' : fragment.slice(0, separator).toLowerCase();
    const allowsSpaces = scope === 'configs' || scope === 'secrets';
    if (/[{}()]/.test(fragment) || (!allowsSpaces && /\s/.test(fragment))) {
      this.closeCompletions();
      return;
    }

    this.completions.set(this.buildCompletions(fragment, opening + 2, cursor, value.slice(cursor)));
    this.selectedCompletion.set(0);
  }

  protected handleKeydown(event: KeyboardEvent, textarea: HTMLTextAreaElement): void {
    const completions = this.completions();
    if (completions.length === 0) return;

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedCompletion.update((index) => (index + 1) % completions.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedCompletion.update((index) => (index - 1 + completions.length) % completions.length);
    } else if (event.key === 'Tab' || event.key === 'Enter') {
      event.preventDefault();
      this.applyCompletion(textarea, this.selectedCompletion());
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.closeCompletions();
    }
  }

  protected chooseCompletion(textarea: HTMLTextAreaElement, index: number, event: MouseEvent): void {
    event.preventDefault();
    this.applyCompletion(textarea, index);
  }

  protected closeCompletions(): void {
    this.completions.set([]);
    this.selectedCompletion.set(0);
  }

  private buildCompletions(fragment: string, replacementStart: number, replacementEnd: number, remainingValue: string): readonly ExpressionCompletion[] {
    const normalizedFragment = fragment.toLowerCase();
    if (!fragment.includes('.')) {
      return this.scopes()
        .filter((scope) => scope.startsWith(normalizedFragment))
        .map((scope) => ({ label: scope, replacement: `${scope}.`, replacementStart, replacementEnd }));
    }

    const separator = fragment.indexOf('.');
    const scope = fragment.slice(0, separator).toLowerCase();
    const prefix = fragment.slice(separator + 1).toLowerCase();
    if (!this.scopes().includes(scope)) return [];

    const closing = remainingValue.startsWith('}}') ? '' : '}}';
    const names = (this.names()[scope] ?? [])
      .filter((name) => name.toLowerCase().startsWith(prefix))
      .map((name) => ({ label: `${scope}.${name}`, replacement: `${scope}.${name}${closing}`, replacementStart, replacementEnd }));
    const functions = scope === 'utils'
      ? this.utilityFunctions()
        .filter((name) => name.toLowerCase().startsWith(prefix))
        .map((name) => ({ label: `${scope}.${name}(...)`, replacement: `${scope}.${name}(`, replacementStart, replacementEnd }))
      : [];
    return [...names, ...functions].slice(0, 50);
  }

  private applyCompletion(textarea: HTMLTextAreaElement, index: number): void {
    const completion = this.completions()[index];
    if (!completion) return;

    const currentValue = textarea.value;
    const value = currentValue.slice(0, completion.replacementStart) + completion.replacement + currentValue.slice(completion.replacementEnd);
    const cursor = completion.replacementStart + completion.replacement.length;
    textarea.value = value;
    this.valueChange.emit(value);
    this.closeCompletions();
    queueMicrotask(() => {
      textarea.focus();
      textarea.setSelectionRange(cursor, cursor);
      if (completion.replacement.endsWith('.')) this.refreshCompletions(textarea);
    });
  }
}
