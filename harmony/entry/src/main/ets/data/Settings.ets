import { asset } from '@kit.AssetStoreKit';
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';

/** Which Notion database Leaf is connected to. The credential itself is in the Asset Store (see Vault). */
export class Connection {
  workspace: string = '';
  dataSourceId: string = '';
  databaseName: string = '';
  titleProperty: string = '';
  /** The 涂鸦墙 row, kept so it never shows up as an essay; empty when the database has none. */
  wallPageId: string = '';
}

/** How an OAuth pair is written to the vault; the same JSON as Android's NotionCredential.encode. */
interface StoredOAuth {
  v?: number;
  kind?: string;
  access?: string;
  refresh?: string;
}

/**
 * What the vault keeps: a pasted integration token, or the pair 用 Notion 登录 gives. Each refresh rotates
 * both tokens, so the pair is always written together.
 */
export class NotionCredential {
  readonly access: string;
  /** Empty for an integration token, which cannot be refreshed. */
  readonly refresh: string;

  constructor(access: string, refresh: string) {
    this.access = access;
    this.refresh = refresh;
  }

  static integration(token: string): NotionCredential {
    return new NotionCredential(token, '');
  }

  static oauth(access: string, refresh: string): NotionCredential {
    return new NotionCredential(access, refresh);
  }

  isOAuth(): boolean {
    return this.refresh.length > 0;
  }

  /** An integration token is stored as the bare string, exactly as before, so older installs read back unchanged. */
  encode(): string {
    if (!this.isOAuth()) {
      return this.access;
    }
    const stored: StoredOAuth = { v: 1, kind: 'oauth', access: this.access, refresh: this.refresh };
    return JSON.stringify(stored);
  }

  /** Null when [stored] looks like a pair but cannot be read: settings then asks to connect again. */
  static decode(stored: string): NotionCredential | null {
    // An integration token never starts with a brace (ntn_… / secret_…).
    if (!stored.startsWith('{')) {
      return NotionCredential.integration(stored);
    }
    let parsed: StoredOAuth;
    try {
      parsed = JSON.parse(stored) as StoredOAuth;
    } catch (e) {
      return null;
    }
    const access = typeof parsed.access === 'string' ? parsed.access : '';
    const refresh = typeof parsed.refresh === 'string' ? parsed.refresh : '';
    if (parsed.kind !== 'oauth' || access.length === 0 || refresh.length === 0) {
      return null;
    }
    return NotionCredential.oauth(access, refresh);
  }
}

const TOKEN_ALIAS: string = 'leaf.notion.token';

function bytes(s: string): Uint8Array {
  return new util.TextEncoder().encodeInto(s);
}

function aliasQuery(): asset.AssetMap {
  const query: asset.AssetMap = new Map();
  query.set(asset.Tag.ALIAS, bytes(TOKEN_ALIAS));
  return query;
}

/**
 * The Notion credential lives in HarmonyOS's Asset Store (hardware-backed, this app only, readable after the
 * first unlock so background sync works), the way Android keeps it in the Keystore-encrypted vault.
 * It is never logged and never leaves the device except in the Authorization header to api.notion.com
 * and, for refreshing an OAuth pair, in the body sent to notion-oauth-relay.
 */
export class Vault {
  static async save(credential: NotionCredential): Promise<void> {
    await Vault.clear();
    const attrs: asset.AssetMap = aliasQuery();
    attrs.set(asset.Tag.SECRET, bytes(credential.encode()));
    attrs.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    await asset.add(attrs);
  }

  /** Null when there is none, or when it can no longer be read. */
  static async credential(): Promise<NotionCredential | null> {
    const stored = await Vault.read();
    return stored.length === 0 ? null : NotionCredential.decode(stored);
  }

  /** The access token for the Authorization header; empty when there is no usable credential. */
  static async token(): Promise<string> {
    const credential = await Vault.credential();
    return credential === null ? '' : credential.access;
  }

  /**
   * A refresh rotated the OAuth pair: swap what the vault holds, but only while it still holds [stale].
   * A disconnect or a new sign-in during the refresh wins; false then, and nothing is written.
   */
  static async replace(stale: NotionCredential, fresh: NotionCredential): Promise<boolean> {
    const held = await Vault.read();
    if (held !== stale.encode()) {
      return false;
    }
    const changes: asset.AssetMap = new Map();
    changes.set(asset.Tag.SECRET, bytes(fresh.encode()));
    try {
      await asset.update(aliasQuery(), changes);
      return true;
    } catch (e) {
      // Removed in the meantime (disconnected): leave it removed.
      return false;
    }
  }

  private static async read(): Promise<string> {
    const query: asset.AssetMap = aliasQuery();
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const found: asset.AssetMap[] = await asset.query(query);
      if (found.length === 0) {
        return '';
      }
      const secret = found[0].get(asset.Tag.SECRET) as Uint8Array;
      return util.TextDecoder.create('utf-8').decodeToString(secret);
    } catch (e) {
      // NOT_FOUND is the normal "not connected yet"; anything else also means no usable credential.
      return '';
    }
  }

  static async clear(): Promise<void> {
    try {
      await asset.remove(aliasQuery());
    } catch (e) {
      // Nothing stored: already clear.
    }
  }
}

/** The 写作台's switches and the connection, kept on this device in Preferences. */
export class Settings {
  private prefs: preferences.Preferences | null = null;

  open(context: common.Context): void {
    const options: preferences.Options = { name: 'leaf' };
    this.prefs = preferences.getPreferencesSync(context, options);
  }

  private get p(): preferences.Preferences {
    if (this.prefs === null) {
      throw new Error('Settings used before open()');
    }
    return this.prefs;
  }

  getString(key: string, fallback: string): string {
    return this.p.getSync(key, fallback) as string;
  }

  getBool(key: string, fallback: boolean): boolean {
    return this.p.getSync(key, fallback) as boolean;
  }

  put(key: string, value: string | boolean): void {
    this.p.putSync(key, value);
    this.p.flush();
  }

  connection(): Connection | null {
    const id = this.getString('conn.dataSourceId', '');
    if (id.length === 0) {
      return null;
    }
    const c = new Connection();
    c.workspace = this.getString('conn.workspace', '');
    c.dataSourceId = id;
    c.databaseName = this.getString('conn.databaseName', '');
    c.titleProperty = this.getString('conn.titleProperty', '');
    c.wallPageId = this.getString('conn.wallPageId', '');
    return c;
  }

  saveConnection(c: Connection): void {
    this.p.putSync('conn.workspace', c.workspace);
    this.p.putSync('conn.dataSourceId', c.dataSourceId);
    this.p.putSync('conn.databaseName', c.databaseName);
    this.p.putSync('conn.titleProperty', c.titleProperty);
    this.p.putSync('conn.wallPageId', c.wallPageId);
    this.p.flush();
  }

  clearConnection(): void {
    this.p.deleteSync('conn.workspace');
    this.p.deleteSync('conn.dataSourceId');
    this.p.deleteSync('conn.databaseName');
    this.p.deleteSync('conn.titleProperty');
    this.p.deleteSync('conn.wallPageId');
    this.p.flush();
  }
}
