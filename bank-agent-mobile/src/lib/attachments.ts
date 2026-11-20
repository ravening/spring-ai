import * as DocumentPicker from 'expo-document-picker';
import type { AttachmentAsset } from './types';

export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024; // matches bank-agent multipart max-file-size

/**
 * Must stay in sync with KnowledgeService.SUPPORTED_EXTENSIONS in bank-agent:
 * the backend indexes uploads with Tika (no OCR), so images are rejected
 * server-side with 422 and are therefore not offered by the picker here.
 */
export const ALLOWED_EXTENSIONS = [
  'txt', 'md', 'markdown', 'pdf', 'doc', 'docx', 'odt', 'rtf', 'csv', 'html', 'htm',
  'json', 'xml', 'xls', 'xlsx', 'ppt', 'pptx',
];

const MIME_BY_EXTENSION: Record<string, string> = {
  txt: 'text/plain', md: 'text/markdown', markdown: 'text/markdown', pdf: 'application/pdf',
  csv: 'text/csv', html: 'text/html', htm: 'text/html', json: 'application/json',
  xml: 'application/xml',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  odt: 'application/vnd.oasis.opendocument.text',
  rtf: 'application/rtf',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ppt: 'application/vnd.ms-powerpoint',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
};

export function extensionOf(nameOrUri: string): string {
  const clean = nameOrUri.split('?')[0];
  const dot = clean.lastIndexOf('.');
  return dot >= 0 ? clean.slice(dot + 1).toLowerCase() : '';
}

export function fileNameFromUri(uri: string): string {
  const parts = uri.split('/');
  return decodeURIComponent(parts[parts.length - 1] || 'attachment');
}

export function guessMimeType(nameOrUri: string): string {
  return MIME_BY_EXTENSION[extensionOf(nameOrUri)] ?? 'application/octet-stream';
}

/** Returns a human-readable problem, or null when the file is acceptable. */
export function validateAttachment(asset: AttachmentAsset): string | null {
  if (!asset.name) return 'This file has no name.';
  if (asset.sizeBytes !== undefined && asset.sizeBytes > MAX_UPLOAD_BYTES) {
    return `File is larger than the ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)} MB limit.`;
  }
  const extension = extensionOf(asset.name);
  if (extension && !ALLOWED_EXTENSIONS.includes(extension)) {
    return `.${extension} files are not supported. Upload a document (pdf, docx, txt, csv, html, …).`;
  }
  return null;
}

/** Optional size field used by the validation helper; not part of the upload wire type. */
export type PickedAsset = AttachmentAsset & { sizeBytes?: number };

export async function pickDocument(): Promise<PickedAsset | null> {
  const result = await DocumentPicker.getDocumentAsync({
    type: '*/*',
    multiple: false,
    copyToCacheDirectory: true,
  });
  if (result.canceled || !result.assets?.length) return null;
  const picked = result.assets[0];
  return {
    uri: picked.uri,
    name: picked.name,
    mimeType: picked.mimeType || guessMimeType(picked.name),
    sizeBytes: picked.size ?? undefined,
  };
}
