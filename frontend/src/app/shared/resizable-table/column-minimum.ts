import { EN_TRANSLATIONS } from '../../core/i18n/translations/en.translations';
import { ES_UY_TRANSLATIONS } from '../../core/i18n/translations/es-uy.translations';
import { MIN_COLUMN_WIDTH } from './table-width-preferences.service';

const dictionaries: readonly Readonly<Record<string, string>>[] = [EN_TRANSLATIONS, ES_UY_TRANSLATIONS];
const transientLabels: Readonly<Record<string, readonly string[]>> = {
  'pipes.run': ['pipes.runStarting'],
  'alerts.runNow': ['alerts.runStarting'],
};

/** Measure intrinsic protected content with the component's own fonts, controls and padding. */
export function columnMinimum(table: HTMLTableElement, header: HTMLTableCellElement, index: number): number {
  const policy = header.dataset['resizeMinContent'];
  if (!policy) return MIN_COLUMN_WIDTH;

  const document = table.ownerDocument;
  const probe = table.cloneNode(false) as HTMLTableElement;
  probe.removeAttribute('appResizableTable');
  probe.classList.remove('resizable-table--custom');
  probe.classList.add('resizable-table-measure');
  probe.setAttribute('aria-hidden', 'true');
  probe.inert = true;
  const heading = header.cloneNode(true) as HTMLTableCellElement;
  heading.querySelector('.table-column-resize')?.remove();
  probe.createTHead().insertRow().append(heading);
  const source = table.tBodies[0]?.rows[0]?.cells[index];
  const cell = source?.cloneNode(true) as HTMLTableCellElement | undefined;
  if (cell) probe.createTBody().insertRow().append(cell);

  table.parentElement!.append(probe);
  try {
    // Choose each label's widest supported translation, including temporary button states.
    const walker = document.createTreeWalker(probe, NodeFilter.SHOW_TEXT);
    const nodes: Text[] = [];
    while (walker.nextNode()) nodes.push(walker.currentNode as Text);
    for (const node of nodes) {
      const text = node.textContent?.trim();
      if (!text) continue;

      const keys = new Set(dictionaries.flatMap(dictionary => Object.keys(dictionary).filter(key => dictionary[key] === text)));
      for (const key of [...keys]) for (const extra of transientLabels[key] ?? []) keys.add(extra);
      const choices = [text, ...dictionaries.flatMap(dictionary => [...keys].map(key => dictionary[key]).filter(Boolean))];
      node.textContent = widest(node.parentElement!, choices);
    }
    if (cell && policy !== 'actions') {
      const label = cell.querySelector<HTMLElement>('.badge') ?? cell;
      const choices = policy.split('|').flatMap(key => dictionaries.map(dictionary => dictionary[key] ?? key));
      label.textContent = widest(label, choices);
    }
    return Math.max(MIN_COLUMN_WIDTH, Math.ceil(probe.getBoundingClientRect().width));
  } finally {
    probe.remove();
  }
}

function widest(element: HTMLElement, choices: readonly string[]): string {
  const style = getComputedStyle(element);
  const sample = element.ownerDocument.createElement('span');
  for (const property of ['font-family', 'font-size', 'font-weight', 'font-style', 'letter-spacing', 'text-transform']) {
    sample.style.setProperty(property, style.getPropertyValue(property));
  }
  sample.style.whiteSpace = 'nowrap';
  element.append(sample);
  let result = choices[0] ?? '';
  let maximum = -1;
  for (const choice of choices) {
    sample.textContent = choice;
    const width = sample.getBoundingClientRect().width;
    if (width > maximum) { maximum = width; result = choice; }
  }
  sample.remove();
  return result;
}
