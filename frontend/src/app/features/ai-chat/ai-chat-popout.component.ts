import { ChangeDetectionStrategy, Component } from '@angular/core';

import { AiChatComponent } from './ai-chat.component';

@Component({
  selector: 'app-ai-chat-popout',
  imports: [AiChatComponent],
  template: '<app-ai-chat [popout]="true" />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiChatPopoutComponent {}
