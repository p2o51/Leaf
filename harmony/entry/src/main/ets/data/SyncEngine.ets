import { DIVIDER, Essay, EssayBlock } from '../core/Models';
import { DataSourceInfo, Json, LeafSchema, NotionJson, PropertyInfo, RemoteBlock, RemoteRow, TEXT_TYPES } from '../core/NotionJson';
import { OutboxOp, PendingOp, SyncRules } from '../core/SyncRules';
import { OutboxRow } from './LeafDb';
import { Library, Listener, newId } from './Library';
import { LeafNotion, NotionError, NotionFailure } from './NotionApi';
import { Connection, Vault } from './Settings';

export enum SyncState {
  Idle = 'idle',
  Running = 'running',
  /** Will retry by itself (offline, rate limited). */
  Waiting = 'waiting',
  /** Needs 51: the token stopped working or the database is no longer shared. */
  NeedsAttention = 'needs_attention',
}

/** A push runs this long after the last queued change, so a burst of edits goes out together. */
const PUSH_DELAY_MS: number = 1500;
/** The rest of the library is pulled this often while Leaf is in front (the open essay has its own, faster loop). */
const BACKGROUND_PULL_MS: number = 10 * 60 * 1000;

/**
 * Moves changes between LeafDb and Notion. Push drains the outbox in order; pull mirrors the essay rows
 * and never overwrites anything that still has a change waiting. The essay open in the editor is fetched
 * on its own (refreshBody), so sync concentrates on the one page 51 is writing. Same rules as Android.
 */
export class SyncEngine {
  readonly notion: LeafNotion = new LeafNotion();
  private lib: Library;
  private chain: Promise<void> = Promise.resolve();
  private pushTimer: number = -1;
  private pullTimer: number = -1;
  private listeners: Listener[] = [];
  state: SyncState = SyncState.Idle;
  message: string = '';

  constructor(lib: Library) {
    this.lib = lib;
    lib.onQueued = () => this.kick();
  }

  subscribe(listener: Listener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l: Listener) => l !== listener);
    };
  }

  private set(state: SyncState, message: string = ''): void {
    this.state = state;
    this.message = message;
    this.listeners.forEach((l: Listener) => l());
  }

  /** One job at a time, in the order asked; a failed job does not block the next. */
  private serial(job: () => Promise<void>): Promise<void> {
    const run = this.chain.then(job);
    this.chain = run.then(() => {}, () => {});
    return run;
  }

  /** Pushes soon, once edits pause. */
  kick(): void {
    if (this.pushTimer >= 0) {
      clearTimeout(this.pushTimer);
    }
    this.pushTimer = setTimeout(() => {
      this.pushTimer = -1;
      this.run(false);
    }, PUSH_DELAY_MS);
  }

  /** While Leaf is in front: pull everything now and then. */
  startBackground(): void {
    this.stopBackground();
    this.run(true);
    this.pullTimer = setInterval(() => {
      this.run(true);
    }, BACKGROUND_PULL_MS);
  }

  stopBackground(): void {
    if (this.pullTimer >= 0) {
      clearInterval(this.pullTimer);
      this.pullTimer = -1;
    }
  }

  /** Push then (optionally) pull. */
  run(pull: boolean): Promise<void> {
    return this.serial(async () => {
      const c = this.lib.settings.connection();
      if (c === null) {
        return;
      }
      const token = await Vault.token();
      if (token.length === 0) {
        this.set(SyncState.NeedsAttention, '连接凭据不可用，请重新连接 Notion');
        return;
      }
      this.set(SyncState.Running);
      try {
        await this.push(token, c);
        if (pull) {
          await this.pull(token, c);
        }
        this.set(SyncState.Idle);
      } catch (e) {
        this.fail(e as Error);
      }
    });
  }

  private fail(e: Error): void {
    if (e instanceof NotionError) {
      if (e.reason === NotionFailure.RateLimited || e.reason === NotionFailure.Unavailable) {
        this.set(SyncState.Waiting, e.userMessage);
      } else {
        this.set(SyncState.NeedsAttention, e.userMessage);
      }
    } else {
      this.set(SyncState.Waiting, 'Notion 暂时连不上，稍后会自动重试');
    }
  }

  private source(c: Connection): DataSourceInfo {
    return new DataSourceInfo(c.dataSourceId, c.databaseName, [new PropertyInfo('title', c.titleProperty, 'title')]);
  }

  // ---- push -----------------------------------------------------------------------------------

  private async push(token: string, c: Connection): Promise<void> {
    let op = await this.lib.db.nextOp();
    while (op !== null) {
      let finished = false;
      try {
        finished = await this.apply(token, c, op);
      } catch (e) {
        const failure = e instanceof NotionError ? e.reason : NotionFailure.Unavailable;
        const permanent = failure === NotionFailure.InvalidInput || failure === NotionFailure.NotFound;
        // A change Notion rejects for good must not block the queue forever.
        if (permanent && op.attempts >= 2) {
          finished = true;
        } else {
          if (permanent) {
            await this.lib.db.failedOnce(op.seq);
          }
          throw e as Error;
        }
      }
      if (finished) {
        await this.lib.db.done(op.seq);
      }
      op = await this.lib.db.nextOp();
    }
  }

  /** Returns false when the local row changed while it was being pushed, so the op runs again. */
  private async apply(token: string, c: Connection, op: OutboxRow): Promise<boolean> {
    switch (op.op) {
      case OutboxOp.SaveEssay:
        return this.pushEssay(token, c, op.targetId);
      case OutboxOp.SaveBlock:
        return this.pushBlock(token, op.targetId);
      case OutboxOp.DeleteBlock:
        await this.notion.deleteBlock(token, op.targetId);
        return true;
      case OutboxOp.MoveBlock:
        return this.moveBlock(token, op.targetId);
      case OutboxOp.DeleteEssay:
        await this.notion.trashPage(token, op.targetId);
        return true;
      default:
        return true;
    }
  }

  private async pushEssay(token: string, c: Connection, id: string): Promise<boolean> {
    const e = this.lib.essay(id);
    if (e === undefined) {
      return true;
    }
    const title = e.title;
    const color = e.color;
    const pinned = e.pinned;
    if (e.pageId.length === 0) {
      e.pageId = await this.notion.createPage(token, this.source(c), title, LeafSchema.TypeEssay);
      await this.lib.db.upsertEssay(e);
    }
    await this.notion.updateEssay(token, c.titleProperty, e.pageId, title, color, pinned);
    const now = this.lib.essay(id);
    return now === undefined || (now.title === title && now.color === color && now.pinned === pinned);
  }

  private async pageOf(localBlockId: string): Promise<string> {
    const essayId = await this.lib.db.essayOfBlock(localBlockId);
    const e = this.lib.essay(essayId);
    // The essay's own SaveEssay is always earlier in the queue, so it has a page by now.
    if (e === undefined || e.pageId.length === 0) {
      throw new NotionError(NotionFailure.InvalidInput);
    }
    return e.pageId;
  }

  private static writable(b: EssayBlock): boolean {
    return (TEXT_TYPES.has(b.type) && !b.fixed) || b.type === DIVIDER;
  }

  private static payload(b: EssayBlock): Json {
    return b.type === DIVIDER ? NotionJson.divider() : NotionJson.textBlock(b.type, b.text, b.color);
  }

  private async pushBlock(token: string, localId: string): Promise<boolean> {
    const row = await this.lib.db.block(localId);
    if (row === null || !SyncEngine.writable(row)) {
      return true;
    }
    const pageId = await this.pageOf(localId);
    if (row.blockId.length === 0) {
      await this.appendInPlace(token, pageId, row);
    } else if (row.type !== DIVIDER) {
      await this.notion.updateBlock(token, row.blockId, NotionJson.textUpdate(row.type, row.text, row.color));
    }
    const now = await this.lib.db.block(localId);
    return now === null || !now.contentDiffers(row);
  }

  /** Appends [row] after its nearest synced neighbour and links it; returns the new Notion id or ''. */
  private async appendInPlace(token: string, pageId: string, row: EssayBlock): Promise<string> {
    const essayId = await this.lib.db.essayOfBlock(row.id);
    const body = await this.lib.db.blocksOf(essayId);
    const anchor = SyncRules.anchorFor(body.map((b: EssayBlock) => {
      // The row itself counts as unsynced while it is being (re)written.
      if (b.id !== row.id) {
        return b;
      }
      const copy = b.withText(b.text, b.type);
      copy.blockId = '';
      return copy;
    }), row.id);
    const created = await this.notion.append(token, pageId, [SyncEngine.payload(row)], anchor.afterBlockId, anchor.atStart);
    if (created.length === 0) {
      return '';
    }
    // Removed, or restyled into another type, while it was being created: this block has no use.
    const now = await this.lib.db.block(row.id);
    if (now === null || now.type !== row.type) {
      await this.notion.deleteBlock(token, created[0]);
      return '';
    }
    await this.lib.db.setBlockId(row.id, created[0]);
    return created[0];
  }

  /**
   * Writes a dragged or restyled block again after its new neighbour, and only then removes the old one.
   * The old block's delete is queued as its own op the moment the copy is linked, so a failed delete is
   * retried by Notion id instead of being lost when this op runs again with the new id.
   */
  private async moveBlock(token: string, localId: string): Promise<boolean> {
    const row = await this.lib.db.block(localId);
    if (row === null) {
      return true;
    }
    if (row.blockId.length === 0) {
      return this.pushBlock(token, localId);
    }
    if (!SyncEngine.writable(row)) {
      return true;
    }
    const old = row.blockId;
    const pageId = await this.pageOf(localId);
    const created = await this.appendInPlace(token, pageId, row);
    if (created.length > 0) {
      await this.lib.db.enqueue([new PendingOp(OutboxOp.DeleteBlock, old)]);
    }
    return true;
  }

  // ---- pull -----------------------------------------------------------------------------------

  private async pull(token: string, c: Connection): Promise<void> {
    const rows = (await this.notion.rows(token, this.source(c)))
      .filter((r: RemoteRow) => !r.isWall && r.pageId !== c.wallPageId);
    const waiting = await this.lib.waitingTargets();
    const local = this.lib.essays.slice();
    for (const r of rows) {
      // Deleted in Leaf and not yet trashed in Notion: pulling it back would undo the delete.
      if (waiting.has(r.pageId)) {
        continue;
      }
      const mine = local.find((e: Essay) => e.pageId === r.pageId);
      if (mine !== undefined && waiting.has(mine.id)) {
        continue;
      }
      const changed = mine === undefined || mine.editedAt !== r.editedAt;
      const e = mine ?? new Essay(newId(), r.title);
      if (changed) {
        const first = await this.notion.children(token, r.pageId, 8);
        const words: string[] = [];
        for (const b of first) {
          const rb = NotionJson.bodyBlock(b);
          if (rb !== null && rb.editable) {
            words.push(rb.text);
          }
        }
        e.preview = words.join(' ').trim().substring(0, 400);
        e.bodyLoaded = false;
      }
      e.pageId = r.pageId;
      e.title = r.title;
      e.color = r.color;
      e.pinned = r.pinned;
      e.editedAt = r.editedAt;
      e.createdAt = r.createdAt > 0 ? r.createdAt : e.createdAt;
      e.tags = r.tags;
      e.coverUrl = r.coverUrl;
      await this.lib.db.upsertEssay(e);
    }
    const remoteIds = new Set<string>(rows.map((r: RemoteRow) => r.pageId));
    for (const e of local) {
      if (e.pageId.length > 0 && !remoteIds.has(e.pageId) && !waiting.has(e.id)) {
        await this.lib.db.deleteEssay(e.id);
      }
    }
    await this.lib.reload();
  }

  /** Loads one essay's body unless it is loaded already. Resolves true when the body changed. */
  loadBody(essayId: string): Promise<boolean> {
    return this.fetchBody(essayId, false);
  }

  /**
   * Fetches the essay open in the editor again, so edits made elsewhere (Notion, the phone, the Mac)
   * reach the open window. Only that one page, and only while nothing of it waits to go out.
   */
  refreshBody(essayId: string): Promise<boolean> {
    return this.fetchBody(essayId, true);
  }

  private fetchBody(essayId: string, again: boolean): Promise<boolean> {
    let changed = false;
    return this.serial(async () => {
      const c = this.lib.settings.connection();
      const e = this.lib.essay(essayId);
      if (c === null || e === undefined || e.pageId.length === 0 || (e.bodyLoaded && !again)) {
        return;
      }
      const token = await Vault.token();
      if (token.length === 0) {
        return;
      }
      const waiting = await this.lib.waitingTargets();
      const before = await this.lib.db.blocksOf(essayId);
      if (!SyncRules.canRefresh(essayId, before, waiting)) {
        return;
      }
      let remote: RemoteBlock[] = [];
      try {
        remote = await this.notion.essayBody(token, e.pageId);
      } catch (err) {
        // Offline or refused: the open page keeps what it has and tries again on the next beat.
        return;
      }
      const rows = SyncRules.fetchedBody(before, remote, waiting, newId);
      // The editor writes without this queue, so typing during the fetch must win over the fetched body.
      const now = await this.lib.db.blocksOf(essayId);
      const untouched = now.length === before.length && now.every((b: EssayBlock, i: number) => b.sameAs(before[i]));
      if (!untouched) {
        return;
      }
      changed = rows.length !== before.length || rows.some((b: EssayBlock, i: number) => !b.sameAs(before[i]));
      if (changed) {
        await this.lib.db.replaceBody(essayId, rows);
      }
      e.blocks = rows;
      e.bodyLoaded = true;
      await this.lib.db.upsertEssay(e);
    }).then(() => changed);
  }

  // ---- connecting -----------------------------------------------------------------------------

  /** Step 1: check the token and list the databases shared with it. */
  async discover(token: string): Promise<DataSourceInfo[]> {
    const clean = token.trim();
    this.lastWorkspace = await this.notion.workspaceName(clean);
    return this.notion.dataSources(clean);
  }

  private lastWorkspace: string = '';

  /**
   * Step 2: add any missing fields, remember the 涂鸦墙 row so it never shows as an essay, save the
   * connection and pull everything.
   */
  async connect(token: string, picked: DataSourceInfo): Promise<void> {
    const clean = token.trim();
    const source = await this.notion.ensureSchema(clean, await this.notion.dataSource(clean, picked.id));
    if (source.titleProperty.length === 0) {
      throw new NotionError(NotionFailure.InvalidResponse);
    }
    const rows = await this.notion.rows(clean, source);
    const walls = rows.filter((r: RemoteRow) => r.isWall).sort((a: RemoteRow, b: RemoteRow) => a.editedAt - b.editedAt);
    const c = new Connection();
    c.workspace = this.lastWorkspace;
    c.dataSourceId = source.id;
    c.databaseName = source.name;
    c.titleProperty = source.titleProperty;
    c.wallPageId = walls.length > 0 ? walls[0].pageId : '';
    // Essays written before the first connection are kept and go up to the new database; switching from
    // another database drops its local copies (Notion keeps them).
    const previous = this.lib.settings.connection();
    if (previous !== null && previous.dataSourceId !== source.id) {
      await this.lib.db.clearAll();
    }
    await Vault.save(clean);
    this.lib.settings.saveConnection(c);
    await this.lib.reload();
    await this.run(true);
  }

  /** Forgets the token and every local copy; Notion keeps everything. */
  async disconnect(): Promise<void> {
    await Vault.clear();
    this.lib.settings.clearConnection();
    await this.lib.db.clearAll();
    await this.lib.reload();
    this.set(SyncState.Idle);
  }
}
