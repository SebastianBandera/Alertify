import { ChangeDetectionStrategy, Component, ViewEncapsulation, computed, input } from '@angular/core';

import { renderAiChatMessage } from './ai-chat-message';

@Component({
  selector: 'app-ai-chat-message',
  template: '<div class="ai-chat__formatted-message" [innerHTML]="html()"></div>',
  styleUrl: './ai-chat-message.component.css',
  // Rendered Markdown does not receive Angular's emulated style attributes.
  // Every CSS selector is scoped to the formatted message container instead.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiChatMessageComponent {
  readonly content = input.required<string>();
  protected readonly html = computed(() => renderAiChatMessage(this.content()));
}
