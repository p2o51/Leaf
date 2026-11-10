import { rcp } from '@kit.RemoteCommunicationKit';
import {
  DataSourceInfo, Json, LeafSchema, NotionBlock, NotionDataSource, NotionJson, NotionList, NotionPage, NotionUser, RemoteBlock,
  RemoteRow,
} from '../core/NotionJson';
import { NotionColor } from '../core/Models';

export enum NotionFailure {
  Unauthorized = 'unauthorized',
  Forbidden = 'forbidden',
  NotFound = 'not_found',
  InvalidInput = 'invalid_input',
  RateLimited = 'rate_limited',
  Unavailable = 'unavailable',
  InvalidResponse = 'invalid_response',
}

export class NotionError extends Error {
  readonly reason: NotionFailure;
  readonly status: number;

  constructor(reason: NotionFailure, status: number = 0) {
    super(`Notion: ${reason}${status > 0 ? ` (${status})` : ''}`);
    this.reason = reason;
    this.status = status;
  }

  /** What to tell 51. Never includes the token or a request body. */
  get userMessage(): string {
    switch (this.reason) {
      case NotionFailure.Unauthorized:
        return 'Notion 授权失效，重新连接';
      case NotionFailure.Forbidden:
      case NotionFailure.NotFound:
        return 'Leaf 看不到此页面：在 Notion 把数据库分享给集成';
      case NotionFailure.InvalidInput:
        return 'Notion 拒绝修改';
      case NotionFailure.RateLimited:
      case NotionFailure.Unavailable:
        return '连不上 Notion，稍后自动重试';
      default:
        return '无法解析 Notion 返回的内容';
    }
  }

  static failureFor(code: number): NotionFailure {
    switch (code) {
      case 400:
        return NotionFailure.InvalidInput;
      case 401:
        return NotionFailure.Unauthorized;
      case 403:
        return NotionFailure.Forbidden;
      case 404:
        return NotionFailure.NotFound;
      case 429:
      case 529:
        return NotionFailure.RateLimited;
      default:
        return NotionFailure.Unavailable;
    }
  }
}

const BASE: string = 'https://api.notion.com/v1/';
const API_VERSION: string = '2026-03-11';
const MAX_RETRIES: number = 4;
const INTERVAL_MS: number = 350;
const RETRYABLE: Set<number> = new Set<number>([429, 500, 502, 503, 504, 529]);

function sleep(ms: number): Promise<void> {
  return new Promise<void>((resolve: () => void) => {
    setTimeout(resolve, ms);
  });
}

/**
 * Thin HTTP layer for the Notion API, the same contract as Android's NotionClient: calls go one at a time
 * about 350 ms apart (Notion allows about three a second), 429 / 5xx retry with Retry-After or backoff.
 *
 * Uses Remote Communication Kit rather than @kit.NetworkKit's http, whose RequestMethod has no PATCH,
 * and Notion edits pages and blocks with PATCH.
 */
export class NotionClient {
  private session: rcp.Session = rcp.createSession({
    requestConfiguration: { transfer: { timeout: { connectMs: 20000, transferMs: 60000 } } },
  });
  private tail: Promise<void> = Promise.resolve();
  private lastAt: number = 0;

  get(token: string, path: string): Promise<string> {
    return this.call(token, 'GET', path, '');
  }

  post(token: string, path: string, body: Json): Promise<string> {
    return this.call(token, 'POST', path, JSON.stringify(body));
  }

  patch(token: string, path: string, body: Json): Promise<string> {
    return this.call(token, 'PATCH', path, JSON.stringify(body));
  }

  delete(token: string, path: string): Promise<string> {
    return this.call(token, 'DELETE', path, '');
  }

  /** Serializes calls: each waits for the one before it, whatever that one's outcome. */
  private call(token: string, method: string, path: string, body: string): Promise<string> {
    const run = this.tail.then(() => this.send(token, method, path, body));
    this.tail = run.then(() => {}, () => {});
    return run;
  }

  private async send(token: string, method: string, path: string, body: string): Promise<string> {
    const clean = token.trim();
    if (clean.length === 0 || clean.indexOf('\n') >= 0 || clean.indexOf('\r') >= 0) {
      throw new NotionError(NotionFailure.Unauthorized);
    }
    const headers: rcp.RequestHeaders = {
      'authorization': `Bearer ${clean}`,
      'notion-version': API_VERSION,
      'accept': 'application/json',
      'content-type': 'application/json; charset=utf-8',
    };
    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      const wait = INTERVAL_MS - (Date.now() - this.lastAt);
      if (wait > 0) {
        await sleep(wait);
      }
      this.lastAt = Date.now();
      let response: rcp.Response;
      try {
        const request = new rcp.Request(BASE + path, method, headers, body.length > 0 ? body : undefined);
        response = await this.session.fetch(request);
      } catch (e) {
        if (attempt === MAX_RETRIES) {
          throw new NotionError(NotionFailure.Unavailable);
        }
        await sleep(NotionClient.backoff(attempt));
        continue;
      }
      const code = response.statusCode;
      const text = response.toString() ?? '';
      if (code >= 200 && code < 300) {
        return text;
      }
      if (!RETRYABLE.has(code) || attempt === MAX_RETRIES) {
        throw new NotionError(NotionError.failureFor(code), code);
      }
      const retryAfter = Number.parseInt(String(response.headers['retry-after'] ?? ''), 10);
      await sleep(!Number.isNaN(retryAfter) && retryAfter >= 0 && retryAfter <= 600 ? retryAfter * 1000 : NotionClient.backoff(attempt));
    }
    throw new NotionError(NotionFailure.Unavailable);
  }

  private static backoff(attempt: number): number {
    return Math.min(1000 * Math.pow(2, attempt), 30000) + Math.floor(Math.random() * 251);
  }
}

function parse<T>(text: string): T {
  try {
    return JSON.parse(text) as T;
  } catch (e) {
    throw new NotionError(NotionFailure.InvalidResponse);
  }
}

/** The Notion calls Leaf makes, in Leaf's terms. Every call takes the token so nothing here holds a secret. */
export class LeafNotion {
  private client: NotionClient = new NotionClient();

  async workspaceName(token: string): Promise<string> {
    const me = parse<NotionUser>(await this.client.get(token, 'users/me'));
    const ws = me.bot?.workspace_name ?? '';
    return ws.length > 0 ? ws : (me.name ?? 'Notion');
  }

  /** Every database (data source) shared with the integration. */
  async dataSources(token: string): Promise<DataSourceInfo[]> {
    const out = new Map<string, DataSourceInfo>();
    let cursor = '';
    const seen = new Set<string>();
    do {
      const filter: Json = {};
      filter['property'] = 'object';
      filter['value'] = 'data_source';
      const body: Json = {};
      body['filter'] = filter;
      body['page_size'] = 100;
      if (cursor.length > 0) {
        body['start_cursor'] = cursor;
      }
      const page = parse<NotionList<NotionDataSource>>(await this.client.post(token, 'search', body));
      for (const ds of page.results ?? []) {
        const info = NotionJson.dataSource(ds);
        out.set(info.id, info);
      }
      cursor = LeafNotion.next(page.has_more, page.next_cursor, seen);
    } while (cursor.length > 0);
    return Array.from(out.values());
  }

  async dataSource(token: string, id: string): Promise<DataSourceInfo> {
    return NotionJson.dataSource(parse<NotionDataSource>(await this.client.get(token, `data_sources/${id}`)));
  }

  /** Adds 类型 / 卡片颜色 / 置顶 if missing. Needs the integration's "Update content" capability. */
  async ensureSchema(token: string, source: DataSourceInfo): Promise<DataSourceInfo> {
    const missing = LeafSchema.missingProperties(source);
    if (missing === null) {
      return source;
    }
    const body: Json = {};
    body['properties'] = missing;
    return NotionJson.dataSource(parse<NotionDataSource>(await this.client.patch(token, `data_sources/${source.id}`, body)));
  }

  async rows(token: string, source: DataSourceInfo): Promise<RemoteRow[]> {
    const out: RemoteRow[] = [];
    let cursor = '';
    const seen = new Set<string>();
    do {
      const sort: Json = {};
      sort['timestamp'] = 'last_edited_time';
      sort['direction'] = 'descending';
      const body: Json = {};
      body['page_size'] = 100;
      body['sorts'] = [sort];
      if (cursor.length > 0) {
        body['start_cursor'] = cursor;
      }
      const page = parse<NotionList<NotionPage>>(await this.client.post(token, `data_sources/${source.id}/query`, body));
      for (const p of page.results ?? []) {
        if (p.object === 'page' && p.in_trash !== true) {
          out.push(NotionJson.row(p, source.titleProperty));
        }
      }
      cursor = LeafNotion.next(page.has_more, page.next_cursor, seen);
    } while (cursor.length > 0);
    return out;
  }

  async createPage(token: string, source: DataSourceInfo, title: string, type: string): Promise<string> {
    if (source.titleProperty.length === 0) {
      throw new NotionError(NotionFailure.InvalidResponse);
    }
    const page = parse<NotionPage>(await this.client.post(token, 'pages', NotionJson.newPage(source.id, source.titleProperty, title, type)));
    return page.id;
  }

  async updateEssay(token: string, titleProperty: string, pageId: string, title: string, color: NotionColor, pinned: boolean): Promise<void> {
    await this.client.patch(token, `pages/${pageId}`, NotionJson.essayProperties(titleProperty, title, color, pinned));
  }

  /** Moves a page to Notion's trash, where it can still be restored for 30 days. */
  async trashPage(token: string, pageId: string): Promise<void> {
    const body: Json = {};
    body['in_trash'] = true;
    try {
      await this.client.patch(token, `pages/${pageId}`, body);
    } catch (e) {
      if (!(e instanceof NotionError) || e.reason !== NotionFailure.NotFound) {
        throw e as Error;
      }
    }
  }

  /** Top-level children of a page; Leaf does not descend into nested blocks. */
  async children(token: string, blockId: string, limit: number = 0): Promise<NotionBlock[]> {
    const out: NotionBlock[] = [];
    let cursor = '';
    const seen = new Set<string>();
    do {
      const size = limit > 0 ? Math.min(Math.max(limit, 1), 100) : 100;
      const query = `page_size=${size}${cursor.length > 0 ? `&start_cursor=${encodeURIComponent(cursor)}` : ''}`;
      const page = parse<NotionList<NotionBlock>>(await this.client.get(token, `blocks/${blockId}/children?${query}`));
      for (const b of page.results ?? []) {
        out.push(b);
      }
      cursor = limit > 0 ? '' : LeafNotion.next(page.has_more, page.next_cursor, seen);
    } while (cursor.length > 0);
    return out;
  }

  async essayBody(token: string, pageId: string): Promise<RemoteBlock[]> {
    const out: RemoteBlock[] = [];
    for (const b of await this.children(token, pageId)) {
      const r = NotionJson.bodyBlock(b);
      if (r !== null) {
        out.push(r);
      }
    }
    return out;
  }

  /** Appends blocks and returns the created block ids, in order. */
  async append(token: string, parentId: string, blocks: Json[], afterBlockId: string, atStart: boolean): Promise<string[]> {
    const res = parse<NotionList<NotionBlock>>(
      await this.client.patch(token, `blocks/${parentId}/children`, NotionJson.children(blocks, afterBlockId, atStart)));
    return (res.results ?? []).map((b: NotionBlock) => b.id);
  }

  async updateBlock(token: string, blockId: string, body: Json): Promise<void> {
    await this.client.patch(token, `blocks/${blockId}`, body);
  }

  async deleteBlock(token: string, blockId: string): Promise<void> {
    try {
      await this.client.delete(token, `blocks/${blockId}`);
    } catch (e) {
      // Already deleted in Notion: the goal is reached.
      if (!(e instanceof NotionError) || e.reason !== NotionFailure.NotFound) {
        throw e as Error;
      }
    }
  }

  private static next(hasMore: boolean | undefined, cursor: string | null | undefined, seen: Set<string>): string {
    if (hasMore !== true || cursor === undefined || cursor === null || cursor.length === 0 || seen.has(cursor)) {
      return '';
    }
    seen.add(cursor);
    return cursor;
  }
}
