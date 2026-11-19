import {
  MAX_UPLOAD_BYTES, extensionOf, fileNameFromUri, guessMimeType, needsHeicConversion,
  validateAttachment,
} from '../src/lib/attachments';

describe('attachment helpers', () => {
  it('extracts extensions and file names', () => {
    expect(extensionOf('Statement.PDF')).toBe('pdf');
    expect(extensionOf('file:///tmp/a.b.docx?x=1')).toBe('docx');
    expect(fileNameFromUri('file:///tmp/my%20statement.pdf')).toBe('my statement.pdf');
  });

  it('guesses MIME types and detects HEIC', () => {
    expect(guessMimeType('photo.png')).toBe('image/png');
    expect(guessMimeType('contract.docx')).toContain('wordprocessingml');
    expect(guessMimeType('unknown.zzz')).toBe('application/octet-stream');
    expect(needsHeicConversion('IMG_1.HEIC')).toBe(true);
    expect(needsHeicConversion('photo.jpg')).toBe(false);
  });

  it('validates supported files', () => {
    expect(validateAttachment({ uri: 'x', name: 'a.pdf', mimeType: 'application/pdf' })).toBeNull();
    expect(validateAttachment({ uri: 'x', name: 'virus.exe', mimeType: 'application/octet-stream' }))
      .toContain('not supported');
    expect(validateAttachment({
      uri: 'x', name: 'big.pdf', mimeType: 'application/pdf', sizeBytes: MAX_UPLOAD_BYTES + 1,
    })).toContain('larger than');
  });
});
