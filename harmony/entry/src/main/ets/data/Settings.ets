import { asset } from '@kit.AssetStoreKit';
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';

/** Which Notion database Leaf is connected to. The token itself is in the Asset Store (see Vault). */
export class Connection {
  workspace: string = '';
  dataSourceId: string = '';
  databaseName: string = '';
  titleProperty: string = '';
  /** The 涂鸦墙 row, kept so it never shows up as an essay; empty when the database has none. */
  wallPageId: string = '';
}

const TOKEN_ALIAS: string = 'leaf.notion.token';

function bytes(s: string): Uint8Array {
  return new util.TextEncoder().encodeInto(s);
}

/**
 * The Notion token lives in HarmonyOS's Asset Store (hardware-backed, this app only, readable after the
 * first unlock so background sync works), the way Android keeps it in the Keystore-encrypted vault.
 * It is never logged and never leaves the device except in the Authorization header to api.notion.com.
 */
export class Vault {
  static async save(token: string): Promise<void> {
    await Vault.clear();
    const attrs: asset.AssetMap = new Map();
    attrs.set(asset.Tag.ALIAS, bytes(TOKEN_ALIAS));
    attrs.set(asset.Tag.SECRET, bytes(token));
    attrs.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    await asset.add(attrs);
  }

  static async token(): Promise<string> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, bytes(TOKEN_ALIAS));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const found: asset.AssetMap[] = await asset.query(query);
      if (found.length === 0) {
        return '';
      }
      const secret = found[0].get(asset.Tag.SECRET) as Uint8Array;
      return util.TextDecoder.create('utf-8').decodeToString(secret);
    } catch (e) {
      // NOT_FOUND is the normal "not connected yet"; anything else also means no usable token.
      return '';
    }
  }

  static async clear(): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, bytes(TOKEN_ALIAS));
    try {
      await asset.remove(query);
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
