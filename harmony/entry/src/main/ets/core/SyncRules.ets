import { BlockKind, EssayBlock } from './Models';
import { FILE_TYPES, RemoteBlock, TEXT_TYPES } from './NotionJson';

/**
 * The decisions the sync engine makes, kept pure so they are tested on Node with the rest of core/.
 * Behaviour matches the Android SyncEngine (Leaf PR #15): the outbox runs strictly in order, a pull never
 * overwrites anything that still waits to go out, and the open essay is fetched again on its own.
 */
export enum OutboxOp {
  /** Create or update an essay row (title, colour, pinned). Target: the essay's local id. */
  SaveEssay = 'save_essay',
  /** Create or rewrite a body block. Target: the block's local id. */
  SaveBlock = 'save_block',
  /** Remove a body block. Target: the Notion block id (the local row is already gone). */
  DeleteBlock = 'delete_block',
  /** Write a dragged block again after its new neighbour, then delete the old one (Notion has no move). */
  MoveBlock = 'move_block',
  /** Move an essay page to Notion's trash. Target: the Notion page id. */
  DeleteEssay = 'delete_essay',
}

export class PendingOp {
  readonly op: OutboxOp;
  readonly targetId: string;

  constructor(op: OutboxOp, targetId: string) {
    this.op = op;
    this.targetId = targetId;
  }
}

function kindOf(type: string): BlockKind {
  if (TEXT_TYPES.has(type)) {
    return BlockKind.Paragraph;
  }
  if (type === 'image') {
    return BlockKind.Photo;
  }
  if (FILE_TYPES.has(type)) {
    return BlockKind.Attachment;
  }
  return BlockKind.Other;
}

export class SyncRules {
  /** A block read from Notion as Leaf holds it, with its local id. */
  static fromRemote(localId: string, r: RemoteBlock): EssayBlock {
    const b = new EssayBlock(localId, kindOf(r.type), r.type, r.text, r.color, r.fixed, r.url);
    b.blockId = r.blockId;
    return b;
  }

  /**
   * The body fetched again for an essay. Blocks already known keep their local id, so an edit made from
   * the old list still lands on them. A block deleted here whose DeleteBlock has not gone out yet is still
   * in Notion; its op targets the Notion block id, so it is dropped from the fetched body rather than
   * brought back. [waiting] holds every outbox target id.
   */
  static fetchedBody(before: EssayBlock[], remote: RemoteBlock[], waiting: Set<string>, newId: () => string): EssayBlock[] {
    const known = new Map<string, string>();
    for (const b of before) {
      if (b.blockId.length > 0) {
        known.set(b.blockId, b.id);
      }
    }
    return remote.filter((r: RemoteBlock) => !waiting.has(r.blockId))
      .map((r: RemoteBlock) => SyncRules.fromRemote(known.get(r.blockId) ?? newId(), r));
  }

  /** Whether the open essay may be fetched again: nothing of it waits to go out. */
  static canRefresh(essayId: string, blocks: EssayBlock[], waiting: Set<string>): boolean {
    if (waiting.has(essayId)) {
      return false;
    }
    return !blocks.some((b: EssayBlock) => waiting.has(b.id));
  }

  /**
   * The outbox entries an edit needs, from the body before and after it: changed or new blocks are
   * saved, blocks that left are deleted by their Notion id (a block never sent has nothing to delete),
   * and a block whose place among the others changed, or whose type changed, is written again (moved). Shown-only blocks are never touched.
   */
  static opsFor(before: EssayBlock[], after: EssayBlock[]): PendingOp[] {
    const ops: PendingOp[] = [];
    const old = new Map<string, EssayBlock>();
    before.forEach((b: EssayBlock) => old.set(b.id, b));
    const kept = new Set<string>(after.map((b: EssayBlock) => b.id));
    for (const b of before) {
      if (!kept.has(b.id) && b.blockId.length > 0) {
        ops.push(new PendingOp(OutboxOp.DeleteBlock, b.blockId));
      }
    }
    const beforeOrder = before.filter((b: EssayBlock) => kept.has(b.id)).map((b: EssayBlock) => b.id);
    const afterOrder = after.filter((b: EssayBlock) => old.has(b.id)).map((b: EssayBlock) => b.id);
    const moved = SyncRules.moved(beforeOrder, afterOrder);
    for (const b of after) {
      const prev = old.get(b.id);
      if (prev === undefined) {
        ops.push(new PendingOp(OutboxOp.SaveBlock, b.id));
      } else if (moved.has(b.id) || (prev.type !== b.type && b.blockId.length > 0)) {
        // Notion cannot change a block's type in place, so a restyled block is written again where it
        // stands and the old one deleted, the same route as a drag.
        ops.push(new PendingOp(OutboxOp.MoveBlock, b.id));
      } else if (prev.contentDiffers(b)) {
        ops.push(new PendingOp(OutboxOp.SaveBlock, b.id));
      }
    }
    return ops;
  }

  /**
   * The fewest blocks to move so [before] becomes [after]: everything outside the longest run that keeps
   * its order. Dragging one block moves just that block.
   */
  static moved(before: string[], after: string[]): Set<string> {
    const pos = new Map<string, number>();
    before.forEach((id: string, i: number) => pos.set(id, i));
    const seq: number[] = after.map((id: string) => pos.get(id) ?? -1);
    // Longest increasing subsequence, O(n^2): bodies are short and this runs once per edit.
    const len: number[] = seq.map(() => 1);
    const prev: number[] = seq.map(() => -1);
    let best = -1;
    for (let i = 0; i < seq.length; i++) {
      for (let j = 0; j < i; j++) {
        if (seq[j] < seq[i] && len[j] + 1 > len[i]) {
          len[i] = len[j] + 1;
          prev[i] = j;
        }
      }
      if (best < 0 || len[i] > len[best]) {
        best = i;
      }
    }
    const stay = new Set<string>();
    let k = best;
    while (k >= 0) {
      stay.add(after[k]);
      k = prev[k];
    }
    return new Set<string>(after.filter((id: string) => !stay.has(id)));
  }

  /** Where a new or moved block goes in Notion: after its nearest synced block above, or at the start. */
  static anchorFor(body: EssayBlock[], localId: string): Anchor {
    const at = body.findIndex((b: EssayBlock) => b.id === localId);
    for (let i = at - 1; i >= 0; i--) {
      if (body[i].blockId.length > 0) {
        return new Anchor(body[i].blockId, false);
      }
    }
    const later = body.slice(at + 1).some((b: EssayBlock) => b.blockId.length > 0);
    return new Anchor('', later);
  }
}

/** After [afterBlockId], or at the very start when [atStart]; neither means the end. */
export class Anchor {
  readonly afterBlockId: string;
  readonly atStart: boolean;

  constructor(afterBlockId: string, atStart: boolean) {
    this.afterBlockId = afterBlockId;
    this.atStart = atStart;
  }
}
