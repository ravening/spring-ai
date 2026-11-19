import * as DocumentPicker from 'expo-document-picker';
import * as ImageManipulator from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import type { AttachmentAsset } from './types';

export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024; // matches bank-agent multipart max-file-size

export const ALLOWED_EXTENSIONS = [
  'txt', 'md', 'markdown', 'pdf', 'doc', 'docx', 'csv', 'html', 'htm', 'json', 'xml',
  'xls', 'xlsx', 'ppt', 'pptx', 'rtf', 'odt', 'epub', 'log', 'yaml', 'yml',
  'jpg', 'jpeg', 'png', 'heic', 'heif', 'webp', 'gif', 'bmp', 'tif', 'tiff',
];

const MIME_BY_EXTENSION: Record<string, string> = {
  txt: 'text/plain', md: 'text/markdown', markdown: 'text/markdown', pdf: 'application/pdf',
  csv: 'text/csv', html: 'text/html', htm: 'text/html', json: 'application/json',
  xml: 'application/xml', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
  heic: 'image/heic', heif: 'image/heif', webp: 'image/webp', gif: 'image/gif',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
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

export function needsHeicConversion(nameOrUri: string): boolean {
  return ['heic', 'heif'].includes(extensionOf(nameOrUri));
}

/** Returns a human-readable problem, or null when the file is acceptable. */
export function validateAttachment(asset: AttachmentAsset): string | null {
  if (!asset.name) return 'This file has no name.';
  if (asset.sizeBytes !== undefined && asset.sizeBytes > MAX_UPLOAD_BYTES) {
    return `File is larger than the ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)} MB limit.`;
  }
  const extension = extensionOf(asset.name);
  if (extension && !ALLOWED_EXTENSIONS.includes(extension)) {
    return `.${extension} files are not supported.`;
  }
  return null;
}

/** Optional size field used by the validation helper; not part of the upload wire type. */
export type PickedAsset = AttachmentAsset & { sizeBytes?: number };

async function convertHeic(asset: PickedAsset): Promise<PickedAsset> {
  const result = await ImageManipulator.manipulateAsync(
    asset.uri,
    [],
    { format: ImageManipulator.SaveFormat.JPEG, compress: 0.9 },
  );
  const name = asset.name.replace(/\.(heic|heif)$/i, '.jpg');
  return { ...asset, uri: result.uri, name, mimeType: 'image/jpeg' };
}

export async function pickImage(source: 'library' | 'camera'): Promise<PickedAsset | null> {
  const permission =
    source === 'camera'
      ? await ImagePicker.requestCameraPermissionsAsync()
      : await ImagePicker.requestMediaLibraryPermissionsAsync();
  if (!permission.granted) throw new Error('Photo permission denied');

  const result =
    source === 'camera'
      ? await ImagePicker.launchCameraAsync({ quality: 0.9, allowsEditing: false })
      : await ImagePicker.launchImageLibraryAsync({ quality: 0.9, allowsEditing: false });
  if (result.canceled || !result.assets?.length) return null;

  const picked = result.assets[0];
  const name = picked.fileName || fileNameFromUri(picked.uri);
  let asset: PickedAsset = {
    uri: picked.uri,
    name,
    mimeType: guessMimeType(name),
    sizeBytes: picked.fileSize ?? undefined,
  };
  if (needsHeicConversion(name)) asset = await convertHeic(asset);
  return asset;
}

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
