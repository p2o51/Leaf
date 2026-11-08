import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { Essay, EssayBlock, NotionColor } from '../core/Models';
import { OutboxOp, PendingOp, SyncRules } from '../core/SyncRules';
import { LeafDb, OutboxRow } from './LeafDb';
import { Settings } from './Settings';

export type Listener = () => void;

export function newId(): string {
  return util.generateRandomUUID(false);
}

/**
 * The essays as the app sees them: an in-memory list backed by LeafDb. Every edit lands here first and
 * returns at once; the outbox entry it queues is what later reaches Notion (local first, as on Android).
 */
export class Library {
  readonly db: LeafDb = new LeafDb();
  readonly settings: Settings = new Settings();
  private list: Essay[] = [];
  private listeners: Listener[] = [];
  /** Called after anything was queued, so the sync engine can push soon. */
  onQueued: () => void = () => {};

  async open(context: common.Context): Promise<void> {
    this.settings.open(context);
    await this.db.open(context);
    this.list = await this.db.allEssays();
  }

  get essays(): Essay[] {
    return this.list;
  }

  essay(id: string): Essay | undefined {
    return this.list.find((e: Essay) => e.id === id);
  }

  byPage(pageId: string): Essay | undefined {
    return this.list.find((e: Essay) => e.pageId === pageId);
  }

  subscribe(listener: Listener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l: Listener) => l !== listener);
    };
  }

  notify(): void {
    this.listeners.forEach((l: Listener) => l());
  }

  /** Reloads the list after the sync engine changed rows underneath. */
  async reload(): Promise<void> {
    this.list = await this.db.allEssays();
    this.notify();
  }

  async createEssay(): Promise<Essay> {
    const e = new Essay(newId(), '');
    e.createdAt = Date.now();
    e.editedAt = e.createdAt;
    e.bodyLoaded = true;
    await this.db.upsertEssay(e);
    await this.db.enqueue([new PendingOp(OutboxOp.SaveEssay, e.id)]);
    this.list = [e].concat(this.list);
    this.notify();
    this.onQueued();
    return e;
  }

  async updateMeta(id: string, title: string, color: NotionColor, pinned: boolean): Promise<void> {
    const e = this.essay(id);
    if (e === undefined || (e.title === title && e.color === color && e.pinned === pinned)) {
      return;
    }
    e.title = title;
    e.color = color;
    e.pinned = pinned;
    e.editedAt = Date.now();
    await this.db.upsertEssay(e);
    await this.db.enqueue([new PendingOp(OutboxOp.SaveEssay, e.id)]);
    this.notify();
    this.onQueued();
  }

  async deleteEssay(id: string): Promise<void> {
    const e = this.essay(id);
    if (e === undefined) {
      return;
    }
    if (e.pageId.length > 0) {
      await this.db.enqueue([new PendingOp(OutboxOp.DeleteEssay, e.pageId)]);
    }
    await this.db.deleteEssay(id);
    this.list = this.list.filter((x: Essay) => x.id !== id);
    this.notify();
    this.onQueued();
  }

  body(id: string): Promise<EssayBlock[]> {
    return this.db.blocksOf(id);
  }

  /**
   * Writes an essay's new body and queues what Notion needs to hear: saves for new and changed blocks,
   * deletes for blocks that left, moves for blocks dragged to another place. The editors never know
   * Notion block ids (the sync engine fills them in behind them), so ids are carried over from the
   * library and the diff is taken against what the library holds, not against the editor's copy.
   */
  async saveBody(id: string, after: EssayBlock[]): Promise<void> {
    const e = this.essay(id);
    if (e === undefined) {
      return;
    }
    const before = await this.db.blocksOf(id);
    const linked = new Map<string, string>();
    before.forEach((b: EssayBlock) => linked.set(b.id, b.blockId));
    const body = after.map((b: EssayBlock) => {
      const known = linked.get(b.id) ?? '';
      if (known.length === 0 || b.blockId === known) {
        return b;
      }
      const copy = b.withText(b.text, b.type);
      copy.blockId = known;
      return copy;
    });
    await this.db.replaceBody(id, body);
    const ops = SyncRules.opsFor(before, body);
    await this.db.enqueue(ops);
    e.editedAt = Date.now();
    e.blocks = body;
    e.bodyLoaded = true;
    e.preview = e.previewText.substring(0, 400);
    await this.db.upsertEssay(e);
    this.notify();
    if (ops.length > 0) {
      this.onQueued();
    }
  }

  async origins(id: string): Promise<string> {
    return this.db.origins(id);
  }

  async saveOrigins(id: string, data: string): Promise<void> {
    await this.db.saveOrigins(id, data);
  }

  async waitingTargets(): Promise<Set<string>> {
    const rows = await this.db.pending();
    return new Set<string>(rows.map((r: OutboxRow) => r.targetId));
  }
}
