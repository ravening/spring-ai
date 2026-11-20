import {
  ALLOWED_EXTENSIONS, MAX_UPLOAD_BYTES, extensionOf, fileNameFromUri, guessMimeType, validateAttachment,
} from '../src/lib/attachments';

describe('attachment helpers', () => {
  it('extracts extensions and file names', () => {
    expect(extensionOf('Statement.PDF')).toBe('pdf');
    expect(extensionOf('file:///tmp/a.b.docx?x=1')).toBe('docx');
    expect(fileNameFromUri('file:///tmp/my%20statement.pdf')).toBe('my statement.pdf');
  });

  it('guesses MIME types', () => {
    expect(guessMimeType('contract.docx')).toContain('wordprocessingml');
    expect(guessMimeType('notes.md')).toBe('text/markdown');
    expect(guessMimeType('unknown.zzz')).toBe('application/octet-stream');
  });

  it('only allows extensions the backend can index (KnowledgeService.SUPPORTED_EXTENSIONS)', () => {
    for (const ext of ['txt', 'md', 'pdf', 'docx', 'csv', 'html', 'xlsx', 'pptx', 'odt', 'rtf']) {
      expect(ALLOWED_EXTENSIONS).toContain(ext);
    }
    expect(ALLOWED_EXTENSIONS).not.toContain('jpg');
    expect(ALLOWED_EXTENSIONS).not.toContain('png');
    expect(ALLOWED_EXTENSIONS).not.toContain('heic');
  });

  it('validates supported files', () => {
    expect(validateAttachment({ uri: 'x', name: 'a.pdf', mimeType: 'application/pdf' })).toBeNull();
    expect(validateAttachment({ uri: 'x', name: 'photo.jpg', mimeType: 'image/jpeg' }))
      .toContain('not supported');
    expect(validateAttachment({ uri: 'x', name: 'virus.exe', mimeType: 'application/octet-stream' }))
      .toContain('not supported');
    expect(validateAttachment({
      uri: 'x', name: 'big.pdf', mimeType: 'application/pdf', sizeBytes: MAX_UPLOAD_BYTES + 1,
    })).toContain('larger than');
  });
});
