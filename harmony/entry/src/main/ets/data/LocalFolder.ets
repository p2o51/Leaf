import { common } from '@kit.AbilityKit';
import { fileIo as fs, fileShare, fileUri, ListFileOptions, picker } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { DeskPartKind, DeskText } from '../core/DeskText';
import {
  conflictName, contentHash, decide, decode, encode, fileNameFor, FolderAction, isEssayFile, isoTime, LocalDoc,
  LocalMeta, Side, Synced, titleOf,
} from '../core/LocalFiles';
import { Essay, EssayBlock } from '../core/Models';
import { Library, newId } from './Library';
import { Settings } from './Settings';

const DOMAIN: number = 0x4C46;
const URI_KEY: string = 'folder.uri';
const MAP_KEY: string = 'folder.map';
/** The folder the remembered pairs belong to, kept after 停止保存 so choosing it again does not duplicate. */
const LAST_KEY: string = 'folder.last';
/** How often the folder is looked at for edits made in other editors while Leaf is in front (ms). */
const SCAN_EVERY_MS: number = 20000;
/** How long Leaf waits after its own change before writing files (ms), so typing is written once. */
const WRITE_AFTER_MS: number = 2000;
/** A block Leaf shows but does not write as text (a photo, a file) keeps its place in the file as this line. */
const SHOWN_MARK: RegExp = /^<!-- leaf:([A-Za-z0-9_-]+) -->$/;
/** Bodies fetched from Notion per scan for essays never opened here, to stay inside Notion's rate limit. */
const LOADS_PER_SCAN: number = 3;
const FOLDER_SYSCAP: string = 'SystemCapability.FileManagement.UserFileService.FolderSelection';

/** Picking a folder is a 2-in-1 feature (the system picker has no folder mode elsewhere). */
export class FolderPicker {
  static supported(): boolean {
    return canIUse(FOLDER_SYSCAP);
  }
}

/** What Leaf remembers about one essay's file since the two last agreed. */
class Entry {
  file: string = '';
  /** The title the file name stands for (a name can be cut or numbered, so it is not always the title). */
  title: string = '';
  hash: string = '';
  /** The file's mtime (s) when the two last agreed: the same mtime means the file was not touched. */
  mtime: number = 0;
  /** The essay's [signature] when the two last agreed: the same signature means the essay did not change. */
  sig: string = '';
}

interface EntryJson {
  file: string;
  title: string;
  hash: string;
  mtime: number;
  sig: string;
}

/**
 * The folder of Markdown files: one file per essay, front matter for what Markdown has no place for (Notion
 * page, tags, colour, pin), kept in step with the library both ways. The rules are in core/LocalFiles; this is
 * the files and the timing. Nothing here talks to Notion: an essay changed from a file goes out through the
 * library's outbox like any other edit.
 */
export class LocalFolder {
  static readonly shared: LocalFolder = new LocalFolder();
  private lib: Library | null = null;
  private settings: Settings | null = null;
  private loadBody: (id: string) => Promise<boolean> = () => Promise.resolve(false);
  private entries: Map<string, Entry> = new Map<string, Entry>();
  private uri: string = '';
  private running: boolean = false;
  private again: boolean = false;
  private writeTimer: number = -1;
  private scanTimer: number = -1;
  /** What went wrong last, for settings ('' when fine). */
  problem: string = '';

  /** At launch, once the library is open: reads what was remembered and looks at the folder. */
  start(lib: Library, settings: Settings, loadBody: (id: string) => Promise<boolean>): void {
    this.lib = lib;
    this.settings = settings;
    this.loadBody = loadBody;
    this.uri = settings.getString(URI_KEY, '');
    this.entries = LocalFolder.readEntries(settings.getString(MAP_KEY, ''));
    lib.subscribe(() => this.soon());
    if (this.uri.length > 0) {
      this.activate().then(() => this.sync());
    }
  }

  /** While Leaf is in front, the folder is looked at every 20 s for edits made elsewhere. */
  resume(): void {
    if (this.scanTimer < 0) {
      this.scanTimer = setInterval(() => this.sync(), SCAN_EVERY_MS);
    }
    this.sync();
  }

  pause(): void {
    if (this.scanTimer >= 0) {
      clearInterval(this.scanTimer);
      this.scanTimer = -1;
    }
    this.sync();
  }

  /** The folder as a person reads it (文档/随笔 rather than /storage/Users/currentUser/Documents/随笔), '' when none. */
  displayPath(): string {
    if (this.uri.length === 0) {
      return '';
    }
    const path = this.dir().replace(/^\/storage\/Users\/currentUser\//, '');
    const top = new Map<string, string>([['Documents', '文档'], ['Desktop', '桌面'], ['Download', '下载']]);
    const cut = path.indexOf('/');
    const head = cut < 0 ? path : path.substring(0, cut);
    return (top.get(head) ?? head) + (cut < 0 ? '' : path.substring(cut));
  }

  /** Asks for a folder, keeps access to it across launches, and writes every essay into it. */
  async choose(context: common.UIAbilityContext): Promise<void> {
    const options = new picker.DocumentSelectOptions();
    options.selectMode = picker.DocumentSelectMode.FOLDER;
    options.maxSelectNumber = 1;
    const uris: string[] = await new picker.DocumentViewPicker(context).select(options);
    if (uris.length === 0) {
      return;
    }
    const policy: fileShare.PolicyInfo = { uri: uris[0], operationMode: LocalFolder.mode() };
    try {
      await fileShare.persistPermission([policy]);
    } catch (e) {
      throw new Error('无法保留对此文件夹的访问权限。请选择其他文件夹。');
    }
    if (uris[0] !== (this.settings?.getString(LAST_KEY, '') ?? '')) {
      // Another folder: nothing in it has been agreed on yet.
      this.entries = new Map<string, Entry>();
    }
    this.settings?.put(LAST_KEY, uris[0]);
    this.uri = uris[0];
    this.settings?.put(URI_KEY, this.uri);
    this.saveEntries();
    await this.activate();
    await this.sync();
  }

  /** Stops keeping the folder in step. The files stay where they are. */
  async forget(): Promise<void> {
    if (this.uri.length > 0) {
      const policy: fileShare.PolicyInfo = { uri: this.uri, operationMode: LocalFolder.mode() };
      fileShare.revokePermission([policy]).catch((e: Error) => LocalFolder.log('revoke', e));
    }
    this.uri = '';
    this.settings?.put(URI_KEY, '');
  }

  private soon(): void {
    if (this.uri.length === 0) {
      return;
    }
    if (this.writeTimer >= 0) {
      clearTimeout(this.writeTimer);
    }
    this.writeTimer = setTimeout(() => {
      this.writeTimer = -1;
      this.sync();
    }, WRITE_AFTER_MS);
  }

  private static mode(): number {
    return fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE;
  }

  private async activate(): Promise<void> {
    const policy: fileShare.PolicyInfo = { uri: this.uri, operationMode: LocalFolder.mode() };
    try {
      await fileShare.activatePermission([policy]);
      this.problem = '';
    } catch (e) {
      this.problem = '无法访问随笔文件夹。请在设置中重新选择。';
      LocalFolder.log('activate', e as Error);
    }
  }

  private dir(): string {
    return new fileUri.FileUri(this.uri).path;
  }

  /** One pass over the folder and the library. Passes never overlap; a pass asked for meanwhile runs after. */
  async sync(): Promise<void> {
    if (this.uri.length === 0 || this.lib === null) {
      return;
    }
    if (this.running) {
      this.again = true;
      return;
    }
    this.running = true;
    try {
      await this.pass(this.lib);
    } catch (e) {
      LocalFolder.log('sync', e as Error);
    }
    this.running = false;
    if (this.again) {
      this.again = false;
      this.sync();
    }
  }

  private async pass(lib: Library): Promise<void> {
    const dir = this.dir();
    let names: string[] = [];
    try {
      const options: ListFileOptions = { recursion: false };
      names = fs.listFileSync(dir, options).filter((n: string) => isEssayFile(n));
    } catch (e) {
      this.problem = '无法读取随笔文件夹。请在设置中重新选择。';
      return;
    }
    this.problem = '';
    const present = new Set<string>(names);
    const claimed = new Set<string>();
    // A file that was renamed outside is found again by the Notion page in its front matter.
    const byPage = new Map<string, string>();
    const unread = names.filter((n: string) => !this.isRemembered(n));
    const docs = new Map<string, LocalDoc>();
    for (const n of unread) {
      const doc = this.read(dir, n);
      if (doc !== null) {
        docs.set(n, doc);
        if (doc.meta.notion.length > 0) {
          byPage.set(doc.meta.notion, n);
        }
      }
    }
    const essays = lib.essays;
    const missing = essays.filter((e: Essay) => {
      const entry = this.entries.get(e.id);
      return entry !== undefined && !present.has(entry.file) && !byPage.has(e.pageId);
    }).length;
    // A folder that lost most of its files at once was more likely moved or unplugged than emptied on purpose.
    const keepEssays = missing > 3 && missing * 5 > this.entries.size;
    if (keepEssays) {
      this.problem = `随笔文件夹中有 ${missing} 篇随笔不见了，Leaf 没有删除它们。`;
    }
    let loads = 0;
    for (const e of essays) {
      if (!e.bodyLoaded) {
        // Never opened here: its words are only in Notion. Fetch a few per pass; write it once they arrive.
        if (loads < LOADS_PER_SCAN && e.pageId.length > 0) {
          loads++;
          this.loadBody(e.id).catch((err: Error) => LocalFolder.log('load', err));
        }
        continue;
      }
      let entry = this.entries.get(e.id);
      let name = entry !== undefined && present.has(entry.file) ? entry.file : (byPage.get(e.pageId) ?? '');
      if (entry !== undefined && name.length > 0 && name !== entry.file) {
        // Renamed outside: the new name carries the title now.
        entry.file = name;
        entry.title = titleOf(name);
        entry.mtime = 0;
      }
      if (name.length > 0) {
        claimed.add(name);
      }
      const file = name.length > 0 ? this.fileSide(dir, name, entry, docs) : null;
      const essay = await this.essaySide(lib, e, entry);
      const last: Synced | null = entry === undefined ? null : { hash: entry.hash };
      const action = decide(file === null ? null : file.side, essay.side, last);
      if (action === FolderAction.DeleteEssay && keepEssays) {
        continue;
      }
      entry = await this.apply(lib, dir, action, e, name, file, essay, entry, present);
      if (entry === undefined) {
        this.entries.delete(e.id);
      } else {
        this.entries.set(e.id, entry);
      }
    }
    // Remembered files whose essay is gone from Leaf, and files that appeared from elsewhere.
    const known = new Set<string>(essays.map((e: Essay) => e.id));
    this.entries.forEach((entry: Entry, id: string) => {
      if (!known.has(id) && present.has(entry.file)) {
        claimed.add(entry.file);
        const file = this.fileSide(dir, entry.file, entry, docs);
        if (decide(file.side, null, { hash: entry.hash }) === FolderAction.DeleteFile) {
          this.toTrash(dir, entry.file);
        }
        this.entries.delete(id);
      } else if (!known.has(id)) {
        this.entries.delete(id);
      }
    });
    for (const n of names) {
      if (!claimed.has(n)) {
        const doc = docs.get(n) ?? this.read(dir, n);
        if (doc !== null) {
          await this.createEssay(lib, dir, n, doc);
        }
      }
    }
    this.saveEntries();
  }

  private isRemembered(name: string): boolean {
    let found = false;
    this.entries.forEach((e: Entry) => {
      found = found || e.file === name;
    });
    return found;
  }

  private read(dir: string, name: string): LocalDoc | null {
    try {
      return decode(fs.readTextSync(`${dir}/${name}`));
    } catch (e) {
      LocalFolder.log('read', e as Error);
      return null;
    }
  }

  private fileSide(dir: string, name: string, entry: Entry | undefined, docs: Map<string, LocalDoc>): FileState {
    const path = `${dir}/${name}`;
    const mtime = fs.statSync(path).mtime;
    if (entry !== undefined && entry.file === name && entry.mtime === mtime) {
      return new FileState({ hash: entry.hash }, null, mtime, entry.title);
    }
    const doc = docs.get(name) ?? this.read(dir, name) ?? new LocalDoc(new LocalMeta(), '');
    const title = entry !== undefined && entry.file === name ? entry.title : titleOf(name);
    const hash = contentHash(title, encode(doc.meta, doc.body));
    return new FileState({ hash: hash }, doc, mtime, title);
  }

  private async essaySide(lib: Library, e: Essay, entry: Entry | undefined): Promise<EssayState> {
    const sig = LocalFolder.signature(e);
    if (entry !== undefined && entry.sig === sig) {
      return new EssayState({ hash: entry.hash }, sig, '');
    }
    const text = encode(LocalFolder.metaOf(e, null), LocalFolder.bodyOf(await lib.body(e.id)));
    return new EssayState({ hash: contentHash(e.title, text) }, sig, text);
  }

  private async apply(lib: Library, dir: string, action: FolderAction, e: Essay, name: string,
    file: FileState | null, essay: EssayState, entry: Entry | undefined, present: Set<string>): Promise<Entry | undefined> {
    switch (action) {
      case FolderAction.Nothing:
        return entry;
      case FolderAction.Record:
        return LocalFolder.agreed(name, file === null ? e.title : file.title, essay.side.hash,
          file === null ? 0 : file.mtime, essay.sig);
      case FolderAction.Conflict:
        if (file !== null && file.doc !== null) {
          // Both changed: Leaf's version takes the name, the other is kept beside it and becomes an essay too.
          this.write(dir, conflictName(name, new Date()), encode(file.doc.meta, file.doc.body));
        }
        return this.writeEssay(lib, dir, e, name, file, essay, present);
      case FolderAction.WriteFile:
      case FolderAction.CreateFile:
        return this.writeEssay(lib, dir, e, name, file, essay, present);
      case FolderAction.ReadFile:
        if (file !== null && file.doc !== null) {
          await this.readInto(lib, e, file.title, file.doc);
          const after = lib.essay(e.id);
          return LocalFolder.agreed(name, file.title, file.side.hash, file.mtime,
            after === undefined ? essay.sig : LocalFolder.signature(after));
        }
        return entry;
      case FolderAction.DeleteEssay:
        await lib.deleteEssay(e.id);
        return undefined;
      case FolderAction.DeleteFile:
        this.toTrash(dir, name);
        return undefined;
      default:
        return entry;
    }
  }

  private async writeEssay(lib: Library, dir: string, e: Essay, name: string, file: FileState | null,
    essay: EssayState, present: Set<string>): Promise<Entry> {
    let target = name;
    if (target.length === 0 || (file !== null && file.title !== e.title)) {
      const taken = new Set<string>(Array.from(present).filter((n: string) => n !== name));
      target = fileNameFor(e.title, taken);
      if (name.length > 0 && target !== name) {
        fs.renameSync(`${dir}/${name}`, `${dir}/${target}`);
        present.delete(name);
      }
      present.add(target);
    }
    // Keys a person added to the front matter (title:, date:…) stay, even when the file itself was not touched.
    const doc = file === null ? null : (file.doc ?? this.read(dir, target));
    const kept = doc === null ? null : doc.meta;
    const text = essay.text.length > 0 && kept === null ? essay.text :
      encode(LocalFolder.metaOf(e, kept), LocalFolder.bodyOf(await lib.body(e.id)));
    this.write(dir, target, text);
    return LocalFolder.agreed(target, e.title, contentHash(e.title, text), fs.statSync(`${dir}/${target}`).mtime, essay.sig);
  }

  private async readInto(lib: Library, e: Essay, title: string, doc: LocalDoc): Promise<void> {
    await lib.updateMeta(e.id, title, doc.meta.color, doc.meta.pinned);
    await lib.setTags(e.id, doc.meta.tags);
    await lib.saveBody(e.id, LocalFolder.blocksFrom(doc.body, await lib.body(e.id)));
  }

  private async createEssay(lib: Library, dir: string, name: string, doc: LocalDoc): Promise<void> {
    const e = await lib.createEssay();
    const title = titleOf(name);
    await this.readInto(lib, e, title, doc);
    const after = lib.essay(e.id) ?? e;
    const text = encode(doc.meta, doc.body);
    this.entries.set(e.id, LocalFolder.agreed(name, title, contentHash(title, text), fs.statSync(`${dir}/${name}`).mtime,
      LocalFolder.signature(after)));
  }

  private write(dir: string, name: string, text: string): void {
    const f = fs.openSync(`${dir}/${name}`, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(f.fd, text);
      fs.fsyncSync(f.fd);
    } finally {
      fs.closeSync(f);
    }
  }

  /** Files of essays deleted in Leaf go to a hidden .trash folder beside them, not straight away. */
  private toTrash(dir: string, name: string): void {
    try {
      const trash = `${dir}/.trash`;
      if (!fs.accessSync(trash)) {
        fs.mkdirSync(trash);
      }
      fs.renameSync(`${dir}/${name}`, `${trash}/${conflictName(name, new Date())}`);
    } catch (e) {
      LocalFolder.log('trash', e as Error);
    }
  }

  /** What the essay looks like for change detection without reading its body. */
  private static signature(e: Essay): string {
    return [e.editedAt, e.pageId, e.title, e.color.apiName, e.pinned, e.tags.join('\u0001')].join('\u0000');
  }

  private static metaOf(e: Essay, kept: LocalMeta | null): LocalMeta {
    const m = new LocalMeta();
    m.notion = e.pageId;
    m.tags = e.tags.slice();
    m.color = e.color;
    m.pinned = e.pinned;
    m.created = kept !== null && kept.created.length > 0 ? kept.created : (e.createdAt > 0 ? isoTime(e.createdAt) : '');
    if (kept !== null) {
      m.extra = kept.extra;
    }
    return m;
  }

  /** The body as Markdown: each run of text as the 写作台 writes it, a shown block as one marked line. */
  private static bodyOf(blocks: EssayBlock[]): string {
    const out: string[] = [];
    for (const p of DeskText.parts(blocks)) {
      if (p.kind === DeskPartKind.Text) {
        if (p.blocks.length > 0) {
          out.push(DeskText.render(p.blocks).text);
        }
      } else if (p.block !== null) {
        out.push(`<!-- leaf:${p.block.id} -->`);
      }
    }
    return out.join('\n\n');
  }

  /** The body back as blocks: text runs matched against the old blocks, marked lines put back as they were. */
  private static blocksFrom(body: string, old: EssayBlock[]): EssayBlock[] {
    const shown = new Map<string, EssayBlock>();
    const editable: EssayBlock[] = [];
    for (const b of old) {
      if (DeskText.editable(b)) {
        editable.push(b);
      } else {
        shown.set(b.id, b);
      }
    }
    const out: EssayBlock[] = [];
    const used = new Set<string>();
    let run: string[] = [];
    const flush = () => {
      const text = run.join('\n').trim();
      run = [];
      if (text.length === 0) {
        return;
      }
      for (const b of DeskText.update(editable.filter((x: EssayBlock) => !used.has(x.id)), text, newId).blocks) {
        used.add(b.id);
        out.push(b);
      }
    };
    for (const line of body.split('\n')) {
      const m = SHOWN_MARK.exec(line.trim());
      const block = m === null ? undefined : shown.get(m[1]);
      if (block !== undefined) {
        flush();
        out.push(block);
      } else {
        run.push(line);
      }
    }
    flush();
    return out;
  }

  private static agreed(file: string, title: string, hash: string, mtime: number, sig: string): Entry {
    const e = new Entry();
    e.file = file;
    e.title = title;
    e.hash = hash;
    e.mtime = mtime;
    e.sig = sig;
    return e;
  }

  private static readEntries(text: string): Map<string, Entry> {
    const out = new Map<string, Entry>();
    if (text.length === 0) {
      return out;
    }
    try {
      const rows = JSON.parse(text) as Record<string, EntryJson>;
      for (const id of Object.keys(rows)) {
        const r = rows[id];
        out.set(id, LocalFolder.agreed(r.file, r.title, r.hash, r.mtime, r.sig));
      }
    } catch (e) {
      LocalFolder.log('entries', e as Error);
    }
    return out;
  }

  private saveEntries(): void {
    const rows: Record<string, EntryJson> = {};
    this.entries.forEach((e: Entry, id: string) => {
      rows[id] = { file: e.file, title: e.title, hash: e.hash, mtime: e.mtime, sig: e.sig };
    });
    this.settings?.put(MAP_KEY, JSON.stringify(rows));
  }

  private static log(what: string, e: Error): void {
    hilog.warn(DOMAIN, 'Leaf', 'folder %{public}s: %{public}s', what, e.message);
  }
}

/** The file half of one pair: its hash, its words when they were read, its mtime and the title it stands for. */
class FileState {
  readonly side: Side;
  readonly doc: LocalDoc | null;
  readonly mtime: number;
  readonly title: string;

  constructor(side: Side, doc: LocalDoc | null, mtime: number, title: string) {
    this.side = side;
    this.doc = doc;
    this.mtime = mtime;
    this.title = title;
  }
}

/** The essay half: its hash, signature and, when it was worked out, its text as a file. */
class EssayState {
  readonly side: Side;
  readonly sig: string;
  readonly text: string;

  constructor(side: Side, sig: string, text: string) {
    this.side = side;
    this.sig = sig;
    this.text = text;
  }
}
