/**
 * Small, dependency-free Markdown parser for assistant answers.
 *
 * Supports the subset the writer agent is asked to produce: headings, paragraphs,
 * bold/italic/inline code/links, bullet and numbered lists, blockquotes, fenced code
 * blocks, horizontal rules and GFM tables. It is intentionally pure so it can be
 * unit-tested in Node without React Native.
 */

export type InlineToken =
  | { type: 'text'; text: string }
  | { type: 'strong'; text: string }
  | { type: 'em'; text: string }
  | { type: 'code'; text: string }
  | { type: 'link'; text: string; href: string };

export type MarkdownBlock =
  | { type: 'heading'; level: number; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'code'; language: string; text: string }
  | { type: 'quote'; text: string }
  | { type: 'table'; headers: string[]; rows: string[][] }
  | { type: 'rule' };

const INLINE_PATTERN = /(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(_([^_]+)_)|(`([^`]+)`)|(\[([^\]]+)\]\(([^)]+)\))/g;

export function parseInline(input: string): InlineToken[] {
  const tokens: InlineToken[] = [];
  let lastIndex = 0;
  for (const match of input.matchAll(INLINE_PATTERN)) {
    const index = match.index ?? 0;
    if (index > lastIndex) tokens.push({ type: 'text', text: input.slice(lastIndex, index) });
    if (match[2] !== undefined) tokens.push({ type: 'strong', text: match[2] });
    else if (match[4] !== undefined) tokens.push({ type: 'em', text: match[4] });
    else if (match[6] !== undefined) tokens.push({ type: 'em', text: match[6] });
    else if (match[8] !== undefined) tokens.push({ type: 'code', text: match[8] });
    else if (match[10] !== undefined && match[11] !== undefined) {
      tokens.push({ type: 'link', text: match[10], href: match[11] });
    }
    lastIndex = index + match[0].length;
  }
  if (lastIndex < input.length) tokens.push({ type: 'text', text: input.slice(lastIndex) });
  return tokens.length ? tokens : [{ type: 'text', text: input }];
}

function isTableSeparator(line: string): boolean {
  const cells = line.trim().replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim());
  if (!cells.length) return false;
  return cells.every((cell) => /^:?-{2,}:?$/.test(cell));
}

function splitTableRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\||\|$/g, '')
    .split('|')
    .map((cell) => cell.trim());
}

export function parseMarkdown(input: string): MarkdownBlock[] {
  if (!input) return [];
  const lines = input.replace(/\r\n?/g, '\n').split('\n');
  const blocks: MarkdownBlock[] = [];
  let i = 0;

  const flushParagraph = (buffer: string[]) => {
    if (buffer.length) {
      blocks.push({ type: 'paragraph', text: buffer.join(' ').trim() });
      buffer.length = 0;
    }
  };

  const paragraph: string[] = [];

  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();

    // Fenced code block
    if (/^```/.test(trimmed)) {
      flushParagraph(paragraph);
      const language = trimmed.slice(3).trim();
      const code: string[] = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i].trim())) {
        code.push(lines[i]);
        i++;
      }
      i++; // closing fence (or EOF)
      blocks.push({ type: 'code', language, text: code.join('\n') });
      continue;
    }

    // Table: header line followed by a |---|---| separator
    if (trimmed.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      flushParagraph(paragraph);
      const headers = splitTableRow(trimmed);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].trim().includes('|') && lines[i].trim() !== '') {
        rows.push(splitTableRow(lines[i]));
        i++;
      }
      blocks.push({ type: 'table', headers, rows });
      continue;
    }

    if (!trimmed) {
      flushParagraph(paragraph);
      i++;
      continue;
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed);
    if (heading) {
      flushParagraph(paragraph);
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2] });
      i++;
      continue;
    }

    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      flushParagraph(paragraph);
      blocks.push({ type: 'rule' });
      i++;
      continue;
    }

    const bullet = /^\s*[-*+]\s+(.*)$/.exec(line);
    const ordered = /^\s*\d+\.\s+(.*)$/.exec(line);
    if (bullet || ordered) {
      flushParagraph(paragraph);
      const isOrdered = Boolean(ordered);
      const items: string[] = [];
      const pattern = isOrdered ? /^\s*\d+\.\s+(.*)$/ : /^\s*[-*+]\s+(.*)$/;
      while (i < lines.length) {
        const match = pattern.exec(lines[i]);
        if (!match) break;
        items.push(match[1]);
        i++;
      }
      blocks.push({ type: 'list', ordered: isOrdered, items });
      continue;
    }

    if (/^>\s?/.test(trimmed)) {
      flushParagraph(paragraph);
      const quote: string[] = [];
      while (i < lines.length && /^>\s?/.test(lines[i].trim())) {
        quote.push(lines[i].trim().replace(/^>\s?/, ''));
        i++;
      }
      blocks.push({ type: 'quote', text: quote.join(' ').trim() });
      continue;
    }

    paragraph.push(trimmed);
    i++;
  }

  flushParagraph(paragraph);
  return blocks;
}

/** Plain-text view used for notification bodies and conversation previews. */
export function stripMarkdown(input: string): string {
  if (!input) return '';
  return input
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+\.\s+/gm, '')
    .replace(/^\s*>#\s?/gm, '')
    .replace(/^\s*#{1,6}\s+/gm, '')
    .replace(/^\s*\|.*\|\s*$/gm, ' ')
    .replace(/\*{1,2}([^*]+)\*{1,2}/g, '$1')
    .replace(/_([^_]+)_/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

export function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return `${text.slice(0, Math.max(0, max - 1)).trimEnd()}…`;
}
