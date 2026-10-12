import MarkdownIt from 'markdown-it';

const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false }).disable('image');

markdown.inline.ruler.before('html_inline', 'safe_inline_formatting', (state, silent) => {
  if (state.src[state.pos] !== '<') return false;

  const match = state.src.slice(state.pos, state.posMax).match(/^<(\/?)(u|sup|sub)>/i);
  if (!match) return false;

  if (!silent) {
    const closing = match[1] === '/';
    const tag = match[2].toLowerCase();
    state.push('html_inline', '', 0).content = `<${closing ? '/' : ''}${tag}>`;
  }
  state.pos += match[0].length;
  return true;
});

markdown.renderer.rules['table_open'] = () => '<div class="ai-chat__table-scroll" tabindex="0"><table class="ai-chat__table">\n';
markdown.renderer.rules['table_close'] = () => '</table></div>\n';
for (const tag of ['th', 'td']) {
  markdown.renderer.rules[`${tag}_open`] = (tokens, index, options, _environment, renderer) => {
    const token = tokens[index];
    const style = token.attrGet('style');
    const alignment = typeof style === 'string' ? style.match(/text-align:\s*(left|center|right)/)?.[1] : undefined;
    if (alignment) {
      token.attrs?.splice(token.attrIndex('style'), 1);
      token.attrSet('class', `ai-chat__align-${alignment}`);
    }
    if (tag === 'th') token.attrSet('scope', 'col');

    return renderer.renderToken(tokens, index, options);
  };
}
markdown.core.ruler.after('inline', 'literal_link_destinations', (state) => {
  for (const block of state.tokens) {
    if (!block.children) continue;

    const children = [];
    for (let index = 0; index < block.children.length; index++) {
      const token = block.children[index];
      if (token.type !== 'link_open') {
        children.push(token);
        continue;
      }

      const destination = new state.Token('text', '', 0);
      destination.content = literalLinkDestination(String(token.attrGet('href') ?? ''));
      children.push(destination);
      while (index + 1 < block.children.length && block.children[index + 1].type !== 'link_close') index++;

      index++;
    }
    block.children = children;
  }
});

export function renderAiChatMessage(content: string): string {
  return markdown.render(content.trimStart()).trim();
}

function literalLinkDestination(destination: string): string {
  try {
    return new URL(destination).href;
  } catch {
    return destination;
  }
}
