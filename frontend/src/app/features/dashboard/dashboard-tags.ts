import type { DashboardViewSettings } from './dashboard-view-settings';

/** Only the shared presentation fields; Alert and Pipe tag scopes remain intact. */
export interface DashboardTag {
  readonly id: number;
  readonly name: string;
  readonly color: string;
}

export interface DashboardTagGroup {
  readonly key: string;
  readonly name: string;
  readonly memberIds: readonly number[];
  readonly color: string;
}

export function dashboardTagKey(name: string): string {
  return name.normalize('NFC').trim().toLowerCase();
}

export function groupDashboardTags(tags: readonly DashboardTag[]): readonly DashboardTagGroup[] {
  const groups = new Map<string, Map<number, DashboardTag>>();
  for (const tag of tags) {
    const key = dashboardTagKey(tag.name);
    const members = groups.get(key) ?? new Map<number, DashboardTag>();
    members.set(tag.id, tag);
    groups.set(key, members);
  }
  return [...groups].map(([key, members]) => {
    const ordered = [...members.values()].sort((left, right) => left.id - right.id);
    const colors = new Set(ordered.map(tag => /^#[\da-f]{6}$/i.test(tag.color) ? tag.color.toLowerCase() : null));
    return {
      key, name: ordered[0].name.normalize('NFC').trim(), memberIds: ordered.map(tag => tag.id),
      color: colors.size === 1 && !colors.has(null) ? ordered[0].color.toLowerCase() : '#667085',
    };
  }).sort((left, right) => left.name.localeCompare(right.name) || left.key.localeCompare(right.key));
}

export function isTagVisible(tag: Pick<DashboardTag, 'id' | 'name'>, settings: DashboardViewSettings): boolean {
  const key = dashboardTagKey(tag.name);
  const choices = settings.tagVisibilityByName;
  return choices && Object.hasOwn(choices, key) ? choices[key] : !settings.hiddenTagIds.includes(tag.id);
}

/** Untagged cards always show. Other cards retain the existing OR between their tags. */
export function hasVisibleTags(tags: readonly Pick<DashboardTag, 'id' | 'name'>[], settings: DashboardViewSettings): boolean {
  return tags.length === 0 || tags.some(tag => isTagVisible(tag, settings));
}

export function tagGroupVisibility(group: DashboardTagGroup, settings: DashboardViewSettings): boolean | 'mixed' {
  const visible = group.memberIds.map(id => isTagVisible({ id, name: group.key }, settings));
  return visible.every(Boolean) ? true : visible.some(Boolean) ? 'mixed' : false;
}

/** A partial legacy selection becomes fully visible on its first explicit group click. */
export function toggleTagGroup(group: DashboardTagGroup, settings: DashboardViewSettings): DashboardViewSettings {
  const visible = tagGroupVisibility(group, settings) !== true;
  const memberIds = new Set(group.memberIds);
  const hidden = settings.hiddenTagIds.filter(id => !memberIds.has(id));
  return {
    ...settings,
    hiddenTagIds: visible ? hidden : [...hidden, ...group.memberIds],
    tagVisibilityByName: { ...settings.tagVisibilityByName, [group.key]: visible },
  };
}
