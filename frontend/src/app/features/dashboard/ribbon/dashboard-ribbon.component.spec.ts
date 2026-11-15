import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocalizationService } from '../../../core/i18n/localization.service';
import { groupDashboardTags } from '../dashboard-tags';
import { DEFAULT_VIEW_SETTINGS } from '../dashboard-view-settings';
import { DashboardRibbonComponent } from './dashboard-ribbon.component';

describe('dashboard grouped tag controls', () => {
  let fixture: ComponentFixture<DashboardRibbonComponent>;
  const tags = groupDashboardTags([
    { id: 3, name: 'Shared', color: '#112233' },
    { id: 12, name: ' SHARED ', color: '#334455' },
  ]);

  beforeEach(async () => {
    vi.stubGlobal('ResizeObserver', class { observe(): void { } disconnect(): void { } });
    await TestBed.configureTestingModule({
      imports: [DashboardRibbonComponent],
      providers: [{ provide: LocalizationService, useValue: { translate: (key: string) => key } }],
    }).compileComponents();
    fixture = TestBed.createComponent(DashboardRibbonComponent);
    fixture.componentRef.setInput('settings', { ...DEFAULT_VIEW_SETTINGS, hiddenTagIds: [3] });
    fixture.componentRef.setInput('tags', tags);
    fixture.componentRef.setInput('searchText', '');
    fixture.detectChanges();
    (fixture.nativeElement.querySelectorAll('.ribbon__menu')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
  });

  afterEach(() => { fixture.destroy(); vi.unstubAllGlobals(); });

  it('renders one native keyboard-accessible checkbox button for the two scopes with a mixed state', () => {
    const chips = fixture.nativeElement.querySelectorAll('.chip--tag') as NodeListOf<HTMLButtonElement>;
    expect(chips).toHaveLength(1);
    expect(chips[0].tagName).toBe('BUTTON');
    expect(chips[0].type).toBe('button');
    expect(chips[0].getAttribute('role')).toBe('checkbox');
    expect(chips[0].getAttribute('aria-checked')).toBe('mixed');
    expect(chips[0].getAttribute('data-tag-key')).toBe('shared');
    expect(chips[0].style.getPropertyValue('--tag-color')).toBe('#667085');
  });

  it('updates the whole group and persists the explicit choice through its model', () => {
    const chip = fixture.nativeElement.querySelector('.chip--tag') as HTMLButtonElement;
    chip.click(); fixture.detectChanges();
    expect(chip.getAttribute('aria-checked')).toBe('true');
    expect(fixture.componentInstance.settings().hiddenTagIds).toEqual([]);
    chip.click(); fixture.detectChanges();
    expect(chip.getAttribute('aria-checked')).toBe('false');
    expect(fixture.componentInstance.settings().hiddenTagIds).toEqual([3, 12]);
    expect(fixture.componentInstance.settings().tagVisibilityByName).toEqual({ shared: false });
  });
});
