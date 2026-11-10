import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { BlockKind, Essay, EssayBlock, NotionColor } from '../core/Models';
import { OutboxOp, PendingOp } from '../core/SyncRules';

/** One change waiting to reach Notion, in the order it was made. */
export class OutboxRow {
  readonly seq: number;
  readonly op: OutboxOp;
  readonly targetId: string;
  readonly attempts: number;

  constructor(seq: number, op: OutboxOp, targetId: string, attempts: number) {
    this.seq = seq;
    this.op = op;
    this.targetId = targetId;
    this.attempts = attempts;
  }
}

const SCHEMA_VERSION: number = 1;

/**
 * Leaf's local library: every essay, the bodies opened so far, the outbox, and 来源 marks. The editor
 * only ever writes here; the sync engine moves things to and from Notion afterwards (local first).
 * Tables mirror the Android Room schema (essays, essay_blocks, outbox) so the two stay easy to compare.
 */
export class LeafDb {
  private store: relationalStore.RdbStore | null = null;

  async open(context: common.Context): Promise<void> {
    const config: relationalStore.StoreConfig = {
      name: 'leaf.db',
      securityLevel: relationalStore.SecurityLevel.S1,
    };
    const store = await relationalStore.getRdbStore(context, config);
    if (store.version < SCHEMA_VERSION) {
      await store.executeSql(`CREATE TABLE IF NOT EXISTS essays (
        id TEXT PRIMARY KEY, pageId TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',
        color TEXT NOT NULL DEFAULT 'default', pinned INTEGER NOT NULL DEFAULT 0, editedAt INTEGER NOT NULL DEFAULT 0,
        createdAt INTEGER NOT NULL DEFAULT 0, preview TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '',
        coverUrl TEXT NOT NULL DEFAULT '', bodyLoaded INTEGER NOT NULL DEFAULT 0)`);
      await store.executeSql(`CREATE TABLE IF NOT EXISTS essay_blocks (
        id TEXT PRIMARY KEY, essayId TEXT NOT NULL, position INTEGER NOT NULL, blockId TEXT NOT NULL DEFAULT '',
        kind TEXT NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', color TEXT NOT NULL DEFAULT 'default',
        fixed INTEGER NOT NULL DEFAULT 0, url TEXT NOT NULL DEFAULT '')`);
      await store.executeSql('CREATE INDEX IF NOT EXISTS idx_blocks_essay ON essay_blocks(essayId)');
      await store.executeSql(`CREATE TABLE IF NOT EXISTS outbox (
        seq INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT NOT NULL, targetId TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)`);
      // 来源 marks describe 51's own writing, so they live only here and never go to Notion.
      await store.executeSql('CREATE TABLE IF NOT EXISTS origins (essayId TEXT PRIMARY KEY, data TEXT NOT NULL)');
      store.version = SCHEMA_VERSION;
    }
    this.store = store;
  }

  private get db(): relationalStore.RdbStore {
    if (this.store === null) {
      throw new Error('LeafDb used before open()');
    }
    return this.store;
  }

  // ---- essays ---------------------------------------------------------------------------------

  async allEssays(): Promise<Essay[]> {
    const rs = await this.db.querySql('SELECT * FROM essays');
    const out: Essay[] = [];
    while (rs.goToNextRow()) {
      const e = new Essay(rs.getString(rs.getColumnIndex('id')), rs.getString(rs.getColumnIndex('title')));
      e.pageId = rs.getString(rs.getColumnIndex('pageId'));
      e.color = NotionColor.fromApi(rs.getString(rs.getColumnIndex('color')));
      e.pinned = rs.getLong(rs.getColumnIndex('pinned')) === 1;
      e.editedAt = rs.getLong(rs.getColumnIndex('editedAt'));
      e.createdAt = rs.getLong(rs.getColumnIndex('createdAt'));
      e.preview = rs.getString(rs.getColumnIndex('preview'));
      const tags = rs.getString(rs.getColumnIndex('tags'));
      e.tags = tags.length > 0 ? tags.split('\n') : [];
      e.coverUrl = rs.getString(rs.getColumnIndex('coverUrl'));
      e.bodyLoaded = rs.getLong(rs.getColumnIndex('bodyLoaded')) === 1;
      out.push(e);
    }
    rs.close();
    return out;
  }

  async upsertEssay(e: Essay): Promise<void> {
    const values: relationalStore.ValuesBucket = {
      'id': e.id,
      'pageId': e.pageId,
      'title': e.title,
      'color': e.color.apiName,
      'pinned': e.pinned ? 1 : 0,
      'editedAt': e.editedAt,
      'createdAt': e.createdAt,
      'preview': e.preview,
      'tags': e.tags.join('\n'),
      'coverUrl': e.coverUrl,
      'bodyLoaded': e.bodyLoaded ? 1 : 0,
    };
    await this.db.insert('essays', values, relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
  }

  async deleteEssay(id: string): Promise<void> {
    await this.db.executeSql('DELETE FROM essay_blocks WHERE essayId = ?', [id]);
    await this.db.executeSql('DELETE FROM essays WHERE id = ?', [id]);
    await this.db.executeSql('DELETE FROM origins WHERE essayId = ?', [id]);
  }

  async clearAll(): Promise<void> {
    await this.db.executeSql('DELETE FROM essay_blocks');
    await this.db.executeSql('DELETE FROM essays');
    await this.db.executeSql('DELETE FROM outbox');
    await this.db.executeSql('DELETE FROM origins');
  }

  // ---- blocks ---------------------------------------------------------------------------------

  async blocksOf(essayId: string): Promise<EssayBlock[]> {
    const rs = await this.db.querySql('SELECT * FROM essay_blocks WHERE essayId = ? ORDER BY position', [essayId]);
    const out: EssayBlock[] = [];
    while (rs.goToNextRow()) {
      const b = new EssayBlock(
        rs.getString(rs.getColumnIndex('id')),
        rs.getString(rs.getColumnIndex('kind')) as BlockKind,
        rs.getString(rs.getColumnIndex('type')),
        rs.getString(rs.getColumnIndex('text')),
        NotionColor.fromApi(rs.getString(rs.getColumnIndex('color'))),
        rs.getLong(rs.getColumnIndex('fixed')) === 1,
        rs.getString(rs.getColumnIndex('url')));
      b.blockId = rs.getString(rs.getColumnIndex('blockId'));
      out.push(b);
    }
    rs.close();
    return out;
  }

  /**
   * Replaces an essay's whole body in one transaction, so a crash never leaves half a page. A Notion block
   * id already linked to a row is kept when the new row has none: only the sync engine links ids, and it
   * may have done so between the editor reading the body and writing it back.
   */
  async replaceBody(essayId: string, blocks: EssayBlock[]): Promise<void> {
    const db = this.db;
    db.beginTransaction();
    try {
      const linked = new Map<string, string>();
      const rs = db.querySqlSync('SELECT id, blockId FROM essay_blocks WHERE essayId = ?', [essayId]);
      while (rs.goToNextRow()) {
        linked.set(rs.getString(0), rs.getString(1));
      }
      rs.close();
      db.executeSync('DELETE FROM essay_blocks WHERE essayId = ?', [essayId]);
      blocks.forEach((b: EssayBlock, i: number) => {
        const values: relationalStore.ValuesBucket = {
          'id': b.id,
          'essayId': essayId,
          'position': i,
          'blockId': b.blockId.length > 0 ? b.blockId : (linked.get(b.id) ?? ''),
          'kind': b.kind,
          'type': b.type,
          'text': b.text,
          'color': b.color.apiName,
          'fixed': b.fixed ? 1 : 0,
          'url': b.url,
        };
        db.insertSync('essay_blocks', values, relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
      });
      db.commit();
    } catch (e) {
      db.rollBack();
      throw e as Error;
    }
  }

  /** Links a local block to the Notion block the outbox just created for it. */
  async setBlockId(localId: string, blockId: string): Promise<void> {
    await this.db.executeSql('UPDATE essay_blocks SET blockId = ? WHERE id = ?', [blockId, localId]);
  }

  async block(localId: string): Promise<EssayBlock | null> {
    const rs = await this.db.querySql('SELECT essayId FROM essay_blocks WHERE id = ?', [localId]);
    let essayId = '';
    if (rs.goToNextRow()) {
      essayId = rs.getString(0);
    }
    rs.close();
    if (essayId.length === 0) {
      return null;
    }
    const body = await this.blocksOf(essayId);
    return body.find((b: EssayBlock) => b.id === localId) ?? null;
  }

  async essayOfBlock(localId: string): Promise<string> {
    const rs = await this.db.querySql('SELECT essayId FROM essay_blocks WHERE id = ?', [localId]);
    let essayId = '';
    if (rs.goToNextRow()) {
      essayId = rs.getString(0);
    }
    rs.close();
    return essayId;
  }

  // ---- outbox ---------------------------------------------------------------------------------

  /** Queues ops in order; one already waiting for the same target is not queued twice. */
  async enqueue(ops: PendingOp[]): Promise<void> {
    if (ops.length === 0) {
      return;
    }
    const waiting = await this.pending();
    const seen = new Set<string>(waiting.map((r: OutboxRow) => `${r.op}:${r.targetId}`));
    for (const op of ops) {
      const key = `${op.op}:${op.targetId}`;
      if (seen.has(key)) {
        continue;
      }
      seen.add(key);
      const values: relationalStore.ValuesBucket = { 'op': op.op, 'targetId': op.targetId, 'attempts': 0 };
      await this.db.insert('outbox', values);
    }
  }

  async pending(): Promise<OutboxRow[]> {
    const rs = await this.db.querySql('SELECT * FROM outbox ORDER BY seq');
    const out: OutboxRow[] = [];
    while (rs.goToNextRow()) {
      out.push(new OutboxRow(rs.getLong(rs.getColumnIndex('seq')), rs.getString(rs.getColumnIndex('op')) as OutboxOp,
        rs.getString(rs.getColumnIndex('targetId')), rs.getLong(rs.getColumnIndex('attempts'))));
    }
    rs.close();
    return out;
  }

  async nextOp(): Promise<OutboxRow | null> {
    const all = await this.pending();
    return all.length > 0 ? all[0] : null;
  }

  async done(seq: number): Promise<void> {
    await this.db.executeSql('DELETE FROM outbox WHERE seq = ?', [seq]);
  }

  async failedOnce(seq: number): Promise<void> {
    await this.db.executeSql('UPDATE outbox SET attempts = attempts + 1 WHERE seq = ?', [seq]);
  }

  // ---- 来源 -----------------------------------------------------------------------------------

  async origins(essayId: string): Promise<string> {
    const rs = await this.db.querySql('SELECT data FROM origins WHERE essayId = ?', [essayId]);
    let data = '';
    if (rs.goToNextRow()) {
      data = rs.getString(0);
    }
    rs.close();
    return data;
  }

  async saveOrigins(essayId: string, data: string): Promise<void> {
    const values: relationalStore.ValuesBucket = { 'essayId': essayId, 'data': data };
    await this.db.insert('origins', values, relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
  }
}
