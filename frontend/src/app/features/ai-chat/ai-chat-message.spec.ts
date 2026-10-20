import { describe, expect, it } from 'vitest';

import { renderAiChatMessage } from './ai-chat-message';

function render(content: string): HTMLElement {
  const element = document.createElement('div');
  element.innerHTML = renderAiChatMessage(content);
  return element;
}

describe('AI chat Markdown formatting', () => {
  it('renders underline, superscript and subscript from the second format example', () => {
    const element = render('- ~~Texto tachado~~ y <u>subrayado</u>\n- Superíndice: x<sup>2</sup>; subíndice: H<sub>2</sub>O');

    expect(element.querySelector('u')?.textContent).toBe('subrayado');
    expect(element.querySelector('sup')?.textContent).toBe('2');
    expect(element.querySelector('sub')?.textContent).toBe('2');
    expect(element.querySelectorAll('li')[1].textContent).toBe('Superíndice: x2; subíndice: H2O');
    expect(element.querySelector('s')?.textContent).toBe('Texto tachado');
  });

  it('supports the allowed tags with nested Markdown and inside tables and quotes', () => {
    const element = render('> <U>**Destacado** y *cursiva*</U>\n\n| Expresión | Valor |\n|---|---|\n| H<sub>2</sub>O | x<sup>2</sup> |');

    expect(element.querySelector('blockquote u strong')?.textContent).toBe('Destacado');
    expect(element.querySelector('blockquote u em')?.textContent).toBe('cursiva');
    expect(element.querySelector('td sub')?.textContent).toBe('2');
    expect(element.querySelector('td sup')?.textContent).toBe('2');
  });

  it('keeps allowed tags literal when escaped, encoded or inside code', () => {
    const element = render('`<u>literal</u>`\n\n```html\nx<sup>2</sup>\nH<sub>2</sub>O\n```\n\n\\<u>escapado\\</u>\n\n&lt;sup&gt;codificado&lt;/sup&gt;');

    expect(element.querySelector('u, sup, sub')).toBeNull();
    expect(element.querySelector('code')?.textContent).toBe('<u>literal</u>');
    expect(element.querySelector('pre code')?.textContent).toContain('x<sup>2</sup>');
    expect(element.textContent).toContain('<sup>codificado</sup>');
  });

  it('does not allow attributes or unrelated HTML even inside the allowed formatting', () => {
    const element = render('<u onclick="alert(1)">bad</u> <sup style="color:red">bad</sup>\n\n<u><img src=x onerror=alert(1)><a href="https://example.com">bad</a><script>alert(1)</script></u>');

    expect(element.querySelector('img, a, script')).toBeNull();
    expect(element.querySelector('[onclick], [style]')).toBeNull();
    expect(element.querySelector('sup')).toBeNull();
    expect(element.querySelectorAll('u')).toHaveLength(1);
    expect(element.querySelector('u')?.textContent).toContain('<script>alert(1)</script>');
  });

  it('preserves inert literal URLs inside the allowed formatting', () => {
    const element = render('<u>[Etiqueta](https://openai.com)</u>');

    expect(element.querySelector('u')?.textContent).toBe('https://openai.com/');
    expect(element.querySelector('a')).toBeNull();
  });

  it('tolerates incomplete or unmatched formatting tags during streaming', () => {
    expect(() => render('</sup> sin apertura <u>pendiente')).not.toThrow();
    expect(render('<u>pendiente').querySelector('u')?.textContent).toBe('pendiente');
    expect(() => render('<u><sup>cruzados</u></sup>')).not.toThrow();
  });

  it('renders italic, combined emphasis, strikethrough and lists from the format example', () => {
    const element = render('Prueba de formato:\n\n- **Negrita**\n- *Cursiva*\n- ***Negrita y cursiva***\n- ~~Tachado~~\n- `Código en línea`');

    expect(element.querySelectorAll('ul > li')).toHaveLength(5);
    expect(element.querySelector('em')?.textContent).toBe('Cursiva');
    expect(element.querySelector('em strong, strong em')?.textContent).toBe('Negrita y cursiva');
    expect(element.querySelector('s')?.textContent).toBe('Tachado');
    expect(element.querySelector('code')?.textContent).toBe('Código en línea');
    expect(element.textContent).not.toContain('*');
  });

  it('supports underscores without formatting underscores inside identifiers', () => {
    const element = render('_cursiva_ __negrita__ ___ambas___ worker_codex_name');

    expect(element.querySelector('em')?.textContent).toBe('cursiva');
    expect(element.querySelector('strong')?.textContent).toBe('negrita');
    expect(element.querySelector('em strong, strong em')?.textContent).toBe('ambas');
    expect(element.textContent).toContain('worker_codex_name');
  });

  it('renders multiline quotes, headings, nested lists and separators', () => {
    const element = render('## Título\n\n> Esto es una cita.\n>\n> Puede ocupar varias líneas.\n\n1. Primero\n   - Anidado\n2. Segundo\n\n---');

    expect(element.querySelector('h2')?.textContent).toBe('Título');
    expect(element.querySelectorAll('blockquote p')).toHaveLength(2);
    expect(element.querySelectorAll('ol > li')).toHaveLength(2);
    expect(element.querySelector('ol ul li')?.textContent).toBe('Anidado');
    expect(element.querySelector('hr')).not.toBeNull();
  });

  it('keeps formatting literal inside inline and fenced code', () => {
    const element = render('`*literal*`\n\n```js\nconst value = "**literal**";\n| A | B |\n|---|---|\n```');

    expect(element.querySelector('code')?.textContent).toBe('*literal*');
    expect(element.querySelector('pre code')?.textContent).toContain('**literal**');
    expect(element.querySelector('pre table')).toBeNull();
    expect(element.querySelector('strong, em')).toBeNull();
  });

  it('retains numeric table alignment and renders italic text in cells', () => {
    const element = render('  Sí.\r\n\r\n| Nodo | Memoria | CPU |\r\n|:---:|---:|---:|\r\n| `worker-codex` | *1,0 GB* | 0,097 % |\r\n\r\nDatos actuales.');

    expect(element.querySelector('p')?.textContent).toBe('Sí.');
    expect(element.querySelectorAll('th')).toHaveLength(3);
    expect(element.querySelector('th')?.className).toBe('ai-chat__align-center');
    expect(element.querySelectorAll('th')[1].className).toBe('ai-chat__align-right');
    expect(element.querySelector('td em')?.textContent).toBe('1,0 GB');
    expect(element.querySelectorAll('p')[1].textContent).toBe('Datos actuales.');
  });

  it('supports escaped delimiters and incomplete streamed Markdown', () => {
    const element = render('\\*literal\\* y **pendiente\n\n| A | B |\n|---|---|\n| A\\|B | _cursiva_ |');

    expect(element.querySelector('p')?.textContent).toBe('*literal* y **pendiente');
    expect(element.querySelector('td')?.textContent).toBe('A|B');
    expect(element.querySelector('td em')?.textContent).toBe('cursiva');
  });

  it('renders line breaks and replaces link labels with inert destination URLs', () => {
    const element = render('Primera línea\nSegunda línea\n\n[OpenAI](https://openai.com/)');

    expect(element.querySelector('br')).not.toBeNull();
    expect(element.querySelectorAll('p')[1].textContent).toBe('https://openai.com/');
    expect(element.querySelector('a')).toBeNull();
    expect(element.textContent).not.toContain('OpenAI');
  });

  it('uses destinations for formatted labels, autolinks, reference links and links in tables or quotes', () => {
    const element = render('[**Nombre** *engañoso*](https://example.com/real)\n\n<https://example.com/auto>\n\n[Referencia][id]\n\n[id]: https://example.com/reference\n\n> [Cita](https://example.com/quote)\n\n| Enlace | Valor |\n|---|---|\n| [Etiqueta](https://example.com/table) | 1 |');

    expect(element.querySelector('a')).toBeNull();
    expect(element.textContent).toContain('https://example.com/real');
    expect(element.textContent).toContain('https://example.com/auto');
    expect(element.textContent).toContain('https://example.com/reference');
    expect(element.querySelector('blockquote')?.textContent?.trim()).toBe('https://example.com/quote');
    expect(element.querySelector('td')?.textContent).toBe('https://example.com/table');
    expect(element.textContent).not.toContain('engañoso');
  });

  it('shows the normalized real URL for a bare hostname without an explicit slash', () => {
    const element = render('[OpenAI](https://openai.com)');

    expect(element.querySelector('p')?.textContent).toBe('https://openai.com/');
    expect(element.querySelector('a')).toBeNull();
  });

  it('keeps URL characters escaped and does not interpret Markdown inside URLs', () => {
    const element = render('[Etiqueta](https://example.com/worker_name?x=1&y=2 "Título")');

    expect(element.querySelector('p')?.textContent).toBe('https://example.com/worker_name?x=1&y=2');
    expect(element.querySelector('a, em')).toBeNull();
  });

  it('keeps HTML and unsafe URLs inert and does not load remote images', () => {
    const element = render('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[unsafe](javascript:alert(1))\n\n[unsafe](data:text/html,payload)\n\n![image](https://example.com/tracker.png)');

    expect(element.querySelector('script, img, iframe')).toBeNull();
    expect(element.querySelector('a')).toBeNull();
    expect(element.textContent).toContain('<script>alert(1)</script>');
  });
});
