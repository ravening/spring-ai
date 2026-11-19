import { parseInline, parseMarkdown, stripMarkdown, truncate } from '../src/lib/markdown';

describe('parseInline', () => {
  it('parses bold, italic, inline code and links', () => {
    const tokens = parseInline('Hello **bold** and *italic* with `code` and [link](https://x.test).');
    expect(tokens).toEqual([
      { type: 'text', text: 'Hello ' },
      { type: 'strong', text: 'bold' },
      { type: 'text', text: ' and ' },
      { type: 'em', text: 'italic' },
      { type: 'text', text: ' with ' },
      { type: 'code', text: 'code' },
      { type: 'text', text: ' and ' },
      { type: 'link', text: 'link', href: 'https://x.test' },
      { type: 'text', text: '.' },
    ]);
  });

  it('handles underscore italics and empty input', () => {
    expect(parseInline('_hi_')).toEqual([{ type: 'em', text: 'hi' }]);
    expect(parseInline('')).toEqual([{ type: 'text', text: '' }]);
  });
});

describe('parseMarkdown', () => {
  it('parses headings, lists, quotes, rules and code fences', () => {
    const blocks = parseMarkdown(
      '# Balance\n\n- Checking\n- Savings\n\n> note\n\n---\n\n```json\n{"a":1}\n```',
    );
    expect(blocks).toEqual([
      { type: 'heading', level: 1, text: 'Balance' },
      { type: 'list', ordered: false, items: ['Checking', 'Savings'] },
      { type: 'quote', text: 'note' },
      { type: 'rule' },
      { type: 'code', language: 'json', text: '{"a":1}' },
    ]);
  });

  it('parses GFM tables including alignment separators', () => {
    const blocks = parseMarkdown(
      '| Account | Balance |\n|:---|---:|\n| Checking | €1,234 |\n| Savings | €500 |',
    );
    expect(blocks).toEqual([
      {
        type: 'table',
        headers: ['Account', 'Balance'],
        rows: [['Checking', '€1,234'], ['Savings', '€500']],
      },
    ]);
  });

  it('parses numbered lists and paragraph inline formatting', () => {
    const blocks = parseMarkdown('1. first\n2. second\n\nA **bold** paragraph.');
    expect(blocks[0]).toEqual({ type: 'list', ordered: true, items: ['first', 'second'] });
    expect(blocks[1]).toEqual({ type: 'paragraph', text: 'A **bold** paragraph.' });
  });

  it('returns no blocks for empty input', () => {
    expect(parseMarkdown('')).toEqual([]);
  });
});

describe('text helpers', () => {
  it('strips markdown for plain text', () => {
    expect(stripMarkdown('## Title\n**Bold** and `code` [link](https://x.test)')).toBe(
      'Title Bold and code link',
    );
  });

  it('truncates long text', () => {
    expect(truncate('abcdefghij', 5)).toBe('abcd…');
    expect(truncate('abc', 5)).toBe('abc');
  });
});
