import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { LocalizationService } from '../../core/i18n/localization.service';

@Component({
  selector: 'app-playwright-remote-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="remote" (wheel)="scroll($event)">
      @if (frame(); as current) {
        <img [src]="current.url" [alt]="t('remoteAlt')" (click)="select($event)" draggable="false">
      } @else {
        <p role="status">{{ t('waitingFrame') }}</p>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .remote { min-height: 200px; overflow: auto; border: 1px solid var(--border, #e4e7ec); border-radius: 10px; background: #101828; }
    img { display: block; width: 100%; height: auto; cursor: crosshair; }
    p { padding: 50px 20px; color: white; text-align: center; }
  `,
})
export class PlaywrightRemoteViewComponent {
  readonly draftId = input.required<string>();
  readonly disabled = input(false);
  readonly elementSelected = output<{ x: number; y: number; frameId: number }>();
  readonly scrolled = output<number>();
  readonly frame = signal<{ url: string; id: number } | null>(null);
  private readonly auth = inject(AuthService);
  private readonly config = inject(RUNTIME_CONFIG);
  private readonly localization = inject(LocalizationService);
  private readonly destroyRef = inject(DestroyRef);
  private socket: WebSocket | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private renewal: ReturnType<typeof setInterval> | null = null;
  private disposed = false;

  constructor() {
    effect(() => { this.draftId(); this.connect(); });
    this.destroyRef.onDestroy(() => {
      this.disposed = true;
      this.socket?.close();
      if (this.timer) clearTimeout(this.timer);
      if (this.renewal) clearInterval(this.renewal);
      const current = this.frame();
      if (current) URL.revokeObjectURL(current.url);
    });
  }

  protected t(key: string): string { return this.localization.translateDynamic('alerts.wizard.' + key); }

  private connect(): void {
    if (this.disposed || !this.draftId()) return;
    if (this.renewal) clearInterval(this.renewal);
    if (this.socket) { this.socket.onclose = null; this.socket.close(); }
    const url = new URL(`${this.config.apiBaseUrl}/api/admin/playwright-frames`);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(url);
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    const authenticate = async (): Promise<void> => {
      try {
        const token = await this.auth.getAccessToken();
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'AUTH', token, draftId: this.draftId() }));
      } catch { socket.close(); }
    };
    socket.onopen = () => { void authenticate(); this.renewal = setInterval(() => void authenticate(), 45_000); };
    socket.onmessage = event => {
      if (!(event.data instanceof ArrayBuffer) || event.data.byteLength <= 8) return;
      const id = Number(new DataView(event.data).getBigInt64(0));
      const url = URL.createObjectURL(new Blob([event.data.slice(8)], { type: 'image/jpeg' }));
      const previous = this.frame();
      this.frame.set({ url, id });
      if (previous) URL.revokeObjectURL(previous.url);
    };
    socket.onclose = () => {
      if (!this.disposed) this.timer = setTimeout(() => this.connect(), 2000);
    };
    socket.onerror = () => socket.close();
  }

  protected select(event: MouseEvent): void {
    const current = this.frame();
    if (this.disabled() || !current) return;
    const rect = (event.target as HTMLImageElement).getBoundingClientRect();
    this.elementSelected.emit({ x: (event.clientX - rect.left) * 1440 / rect.width, y: (event.clientY - rect.top) * 900 / rect.height, frameId: current.id });
  }

  protected scroll(event: WheelEvent): void {
    event.preventDefault();
    if (!this.disabled()) this.scrolled.emit(Math.max(-900, Math.min(900, event.deltaY)));
  }
}
