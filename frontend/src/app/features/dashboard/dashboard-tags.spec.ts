import { afterEach, describe, expect, it } from 'vitest';

import { dashboardTagKey, DashboardTag, groupDashboardTags, hasVisibleTags, isTagVisible, tagGroupVisibility, toggleTagGroup } from './dashboard-tags';
import { DEFAULT_VIEW_SETTINGS, readStoredViewSettings, storeViewSettings } from './dashboard-view-settings';

const TAGS: readonly DashboardTag[] = [
  { id: 12, name: ' Producción ', color: '#ABCDEF' },
  { id: 3, name: 'PRODUCCIO\u0301N', color: '#abcdef' },
];

describe('dashboard tag groups and preferences', () => {
  afterEach(() => localStorage.removeItem('alertify.dashboard.view'));

  it('groups canonical, trimmed case variants and duplicates without removing accents', () => {
    const groups = groupDashboardTags([...TAGS, TAGS[0], { id: 5, name: 'Produccion', color: '#112233' }]);
    expect(groups).toHaveLength(2);
    expect(groups.find(group => group.key === 'producción')).toEqual({
      key: 'producción', name: 'PRODUCCIÓN', memberIds: [3, 12], color: '#abcdef',
    });
    expect(dashboardTagKey(' Produccio\u0301n ')).toBe('producción');
    expect(groupDashboardTags([...TAGS].reverse())).toEqual(groupDashboardTags(TAGS));
    expect(TAGS[0].color).toBe('#ABCDEF');
  });

  it('uses a neutral chip when member colors conflict or are invalid', () => {
    expect(groupDashboardTags([TAGS[0], { ...TAGS[1], color: '#112233' }])[0].color).toBe('#667085');
    expect(groupDashboardTags([{ ...TAGS[0], color: 'red' }])[0].color).toBe('#667085');
  });

  it('keeps a partial legacy choice unchanged through reading and reloading', () => {
    localStorage.setItem('alertify.dashboard.view', JSON.stringify({ hiddenTagIds: [3] }));
    const settings = readStoredViewSettings();
    const group = groupDashboardTags(TAGS)[0];
    expect(tagGroupVisibility(group, settings)).toBe('mixed');
    expect(TAGS.map(tag => isTagVisible(tag, settings))).toEqual([true, false]);
    expect(settings.tagVisibilityByName).toBeUndefined();
    storeViewSettings(settings);
    expect(readStoredViewSettings()).toEqual(settings);
    expect(tagGroupVisibility(group, readStoredViewSettings())).toBe('mixed');
  });

  it('first reveals a mixed group, then hides and reveals every known member', () => {
    const original = { ...DEFAULT_VIEW_SETTINGS, hiddenTagIds: [3, 99] };
    const group = groupDashboardTags(TAGS)[0];
    const visible = toggleTagGroup(group, original);
    expect(visible.hiddenTagIds).toEqual([99]);
    expect(visible.tagVisibilityByName).toEqual({ 'producción': true });
    const hidden = toggleTagGroup(group, visible);
    expect(hidden.hiddenTagIds).toEqual([99, 3, 12]);
    expect(tagGroupVisibility(group, hidden)).toBe(false);
    expect(tagGroupVisibility(group, toggleTagGroup(group, hidden))).toBe(true);
    expect(original.hiddenTagIds).toEqual([3, 99]);
  });

  it('persists a name choice and applies it to a member arriving on a later page', () => {
    const group = groupDashboardTags(TAGS)[0];
    storeViewSettings(toggleTagGroup(group, DEFAULT_VIEW_SETTINGS));
    const hidden = readStoredViewSettings();
    const future = { id: 100, name: ' producción ', color: '#abcdef' };
    expect(isTagVisible(future, hidden)).toBe(false);
    expect(tagGroupVisibility(groupDashboardTags([...TAGS, future])[0], hidden)).toBe(false);
    storeViewSettings(toggleTagGroup(group, hidden));
    expect(isTagVisible(future, readStoredViewSettings())).toBe(true);
  });

  it('retains OR filtering and always shows untagged cards', () => {
    const hidden = toggleTagGroup(groupDashboardTags(TAGS)[0], DEFAULT_VIEW_SETTINGS);
    expect(hasVisibleTags(TAGS, hidden)).toBe(false);
    expect(hasVisibleTags([...TAGS, { id: 20, name: 'Other' }], hidden)).toBe(true);
    expect(hasVisibleTags([], hidden)).toBe(true);
  });

  it('accepts only boolean map values and never reads prototype properties as choices', () => {
    localStorage.setItem('alertify.dashboard.view', '{"tagVisibilityByName":{"producción":false,"bad":"false","__proto__":false,"constructor":true}}');
    const settings = readStoredViewSettings();
    expect(settings.tagVisibilityByName).toEqual({ 'producción': false, ['__proto__']: false, constructor: true });
    expect(isTagVisible({ id: 1, name: '__proto__' }, settings)).toBe(false);
    expect(isTagVisible({ id: 1, name: 'toString' }, settings)).toBe(true);
    const prototype = groupDashboardTags([{ id: 1, name: '__proto__', color: '#112233' }])[0];
    const toggled = toggleTagGroup(prototype, DEFAULT_VIEW_SETTINGS);
    expect(Object.getPrototypeOf(toggled.tagVisibilityByName)).toBe(Object.prototype);
    expect(Object.hasOwn(toggled.tagVisibilityByName!, '__proto__')).toBe(true);
    expect(isTagVisible({ id: 1, name: '__proto__' }, toggled)).toBe(false);
  });

  it.each([null, [], true, 'false'])('ignores an invalid visibility map %s', map => {
    localStorage.setItem('alertify.dashboard.view', JSON.stringify({ tagVisibilityByName: map, hiddenTagIds: [3] }));
    const settings = readStoredViewSettings();
    expect(settings.tagVisibilityByName).toBeUndefined();
    expect(isTagVisible(TAGS[1], settings)).toBe(false);
  });
});
