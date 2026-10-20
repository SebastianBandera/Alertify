import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { LocalizationService } from '../../core/i18n/localization.service';
import { PlaywrightRemoteViewComponent } from './playwright-remote-view.component';

class SocketStub {
  static readonly OPEN = 1;
  static latest: SocketStub;
  readyState = 1;
  onmessage: ((event: { data: ArrayBuffer }) => void) | null = null;
  onclose: (() => void) | null = null;
  send = vi.fn();
  constructor() { SocketStub.latest = this; }
  close(): void { this.onclose?.(); }
}

describe('PlaywrightRemoteViewComponent', () => {
  let fixture: ComponentFixture<PlaywrightRemoteViewComponent>;
  let selected: Mock<(value: { x: number; y: number; frameId: number }) => void>;
  let harness: { loaded(event: Event): void; select(event: MouseEvent): void };
  let sequence = 0;

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.stubGlobal('WebSocket', SocketStub);
    const BaseUrl = URL;
    vi.stubGlobal('URL', class extends BaseUrl {
      static override createObjectURL(): string { return 'blob:frame-' + ++sequence; }
      static override revokeObjectURL(): void { }
    });
    await TestBed.configureTestingModule({
      imports: [PlaywrightRemoteViewComponent],
      providers: [
        { provide: AuthService, useValue: { getAccessToken: vi.fn() } },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: 'https://example.com/alertify' } },
        { provide: LocalizationService, useValue: { translateDynamic: (key: string) => key.replace('alerts.wizard.', '') } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PlaywrightRemoteViewComponent);
    fixture.componentRef.setInput('draftId', 'draft');
    fixture.detectChanges();
    selected = vi.fn();
    fixture.componentInstance.elementSelected.subscribe(selected);
    harness = fixture.componentInstance as unknown as typeof harness;
  });

  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  function receive(id: number): void {
    const bytes = new ArrayBuffer(12);
    new DataView(bytes).setBigInt64(0, BigInt(id));
    SocketStub.latest.onmessage?.({ data: bytes });
    fixture.detectChanges();
  }

  function loaded(): void {
    harness.loaded({ target: { currentSrc: fixture.componentInstance.frame()!.url } } as unknown as Event);
    fixture.detectChanges();
  }

  function click(): void {
    harness.select({ clientX: 30, clientY: 40, target: { getBoundingClientRect: () => ({ left: 10, top: 20, width: 720, height: 450 }) } } as unknown as MouseEvent);
  }

  it('selects only the decoded image, using its own frame identifier', () => {
    receive(1);
    click();
    expect(selected).not.toHaveBeenCalled();
    loaded();
    click();
    expect(selected).toHaveBeenLastCalledWith({ x: 40, y: 40, frameId: 1 });
    receive(2);
    click();
    expect(selected).toHaveBeenCalledTimes(1);
    loaded();
    click();
    expect(selected).toHaveBeenLastCalledWith({ x: 40, y: 40, frameId: 2 });
  });

  it('warns and prevents selection when protected frames stop arriving', () => {
    receive(1);
    loaded();
    expect(fixture.nativeElement.querySelector('.stale')).toBeNull();
    vi.advanceTimersByTime(2000);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.stale')?.textContent).toBe('staleFrame');
    click();
    expect(selected).not.toHaveBeenCalled();
    receive(2);
    loaded();
    expect(fixture.nativeElement.querySelector('.stale')).toBeNull();
  });
});
