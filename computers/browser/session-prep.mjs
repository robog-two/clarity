// Run by entrypoint.sh before Firefox starts: node session-prep.mjs <profile-dir>
//
// Firefox restores the last session's tabs (browser.startup.page=3, see user.js), but under
// WebDriver the tab selected at restore comes back empty (the automation session takes over that
// tab's first load), while every other tab restores. So we add one empty tab to the saved session
// and select it: it is the throwaway one, and all the real tabs come back. Nothing else in the
// session changes. On any problem the files are left untouched (no restore is better than no start).
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const MAGIC = Buffer.from('mozLz40\0', 'latin1');
const BLANK_TAB = {
  entries: [{ url: 'about:blank', title: '', triggeringPrincipal_base64: '{"3":{}}' }],
  index: 1,
  hidden: false,
  attributes: {},
};

/** Decodes a mozlz4 file: magic, u32 LE size, one LZ4 block. */
export function decodeMozLz4(buf) {
  if (buf.length < 12 || !buf.subarray(0, 8).equals(MAGIC)) throw new Error('not a mozlz4 file');
  const size = buf.readUInt32LE(8);
  const src = buf.subarray(12);
  const out = Buffer.alloc(size);
  let i = 0;
  let o = 0;
  while (i < src.length) {
    const token = src[i++];
    let lit = token >> 4;
    if (lit === 15) { let b; do { b = src[i++]; lit += b; } while (b === 255); }
    src.copy(out, o, i, i + lit);
    i += lit;
    o += lit;
    if (i >= src.length) break;
    const offset = src[i] | (src[i + 1] << 8);
    i += 2;
    if (offset === 0 || offset > o) throw new Error('corrupt lz4 block');
    let len = token & 15;
    if (len === 15) { let b; do { b = src[i++]; len += b; } while (b === 255); }
    len += 4;
    if (o + len > size) throw new Error('corrupt lz4 block');
    for (let k = 0; k < len; k++, o++) out[o] = out[o - offset];
  }
  if (o !== size) throw new Error('lz4 size mismatch');
  return out;
}

/** Encodes as mozlz4 with one literal-only LZ4 sequence (valid LZ4, just uncompressed). */
export function encodeMozLz4(data) {
  const header = Buffer.alloc(12);
  MAGIC.copy(header);
  header.writeUInt32LE(data.length, 8);
  const lengthBytes = [];
  let token = data.length;
  if (data.length >= 15) {
    token = 15;
    let rest = data.length - 15;
    while (rest >= 255) { lengthBytes.push(255); rest -= 255; }
    lengthBytes.push(rest);
  }
  return Buffer.concat([header, Buffer.from([token << 4, ...lengthBytes]), data]);
}

const isBlank = (tab) => (tab.entries ?? []).every((e) => !e.url || e.url === 'about:blank');

/**
 * Pure: the session with one empty tab, selected, at the end of the selected window. Empty tabs
 * from earlier restarts are dropped first, so they don't pile up. Null when there is nothing to do.
 */
export function withBlankSelectedTab(state) {
  const windows = state.windows ?? [];
  if (windows.length === 0) return null;
  const w = Math.min(Math.max((state.selectedWindow ?? 1) - 1, 0), windows.length - 1);
  const win = windows[w];
  const tabs = win.tabs ?? [];
  const real = tabs.filter((t) => !isBlank(t));
  if (real.length === 0) return null; // nothing worth restoring
  if (real.length === tabs.length - 1 && isBlank(tabs[tabs.length - 1]) && win.selected === tabs.length) return null; // already done
  const next = { ...state, windows: windows.slice() };
  next.windows[w] = { ...win, tabs: [...real, BLANK_TAB], selected: real.length + 1 };
  return next;
}

export function prepare(profile) {
  const files = ['sessionstore.jsonlz4', 'sessionstore-backups/recovery.jsonlz4', 'sessionstore-backups/recovery.baklz4']
    .map((f) => join(profile, f))
    .filter((f) => existsSync(f));
  for (const file of files) {
    try {
      const state = JSON.parse(decodeMozLz4(readFileSync(file)).toString('utf8'));
      const next = withBlankSelectedTab(state);
      if (!next) continue;
      writeFileSync(`${file}.tmp`, encodeMozLz4(Buffer.from(JSON.stringify(next), 'utf8')));
      renameSync(`${file}.tmp`, file);
    } catch (e) {
      console.error(`clarity-browser: left ${file} as it was: ${e.message}`);
    }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) prepare(process.argv[2] ?? '.');
