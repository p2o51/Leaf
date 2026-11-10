import { common, OpenLinkOptions } from '@kit.AbilityKit';
import { url } from '@kit.ArkTS';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { rcp } from '@kit.RemoteCommunicationKit';
import { NotionError, NotionFailure } from './NotionApi';
import { NotionCredential, Vault } from './Settings';

/**
 * Ported from the Android app. Notion's public-connection OAuth goes through notion-oauth-relay (the Worker
 * shared with Android, Sheaf, 墨记 and CronPaw), because exchanging and refreshing need the client secret,
 * which must never ship in the app. The relay sends Notion's callback on to leaf://notion-oauth; the code is
 * then exchanged by a POST from the app, so it is never left in a URL the relay logs.
 */
export class NotionOAuthConfig {
  static readonly RELAY: string = 'https://notion-oauth-relay.p2o51willam.workers.dev';
  static readonly APP: string = 'leaf';
  static readonly SCHEME: string = 'leaf';
  static readonly HOST: string = 'notion-oauth';
}

const EXPIRED: string = '登录已过期。请重试。';
const NO_CODE: string = 'Notion 未完成授权。请重试。';
const NO_BROWSER: string = '无法打开浏览器。请重试。';
const OFFLINE: string = '无法连接 Notion。请稍后重试。';
const STATE_BYTES: number = 24;

export enum NotionOAuthOutcome {
  Code = 'code',
  /** Cancel on Notion's page: nothing to say, settings just stays where it was. */
  Cancelled = 'cancelled',
  Failed = 'failed',
}

/** What came back to leaf://notion-oauth: a code to exchange, or why there is none. */
export class NotionOAuthResult {
  readonly outcome: NotionOAuthOutcome;
  /** Set for Code. */
  readonly code: string;
  /** Set for Failed: what to tell 51. */
  readonly message: string;

  constructor(outcome: NotionOAuthOutcome, code: string = '', message: string = '') {
    this.outcome = outcome;
    this.code = code;
    this.message = message;
  }
}

/** The relay's answer, which is Notion's token response, reduced to what Leaf keeps. */
export class NotionOAuthTokens {
  readonly access: string;
  readonly refresh: string;
  /** Notion's name for the workspace that was authorized; may be empty. */
  readonly workspace: string;

  constructor(access: string, refresh: string, workspace: string) {
    this.access = access;
    this.refresh = refresh;
    this.workspace = workspace;
  }
}

interface CodeBody {
  code: string;
}

interface RefreshBody {
  refresh_token: string;
}

interface TokenResponse {
  access_token?: string;
  refresh_token?: string;
  workspace_name?: string | null;
}

function hex(data: Uint8Array): string {
  let out = '';
  for (let i = 0; i < data.length; i++) {
    out += data[i].toString(16).padStart(2, '0');
  }
  return out;
}

/** Compares every character whatever the first difference, so timing says nothing about the state. */
function sameState(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

/** leaf://notion-oauth, followed by nothing or by its query; any other link is not ours. */
function isCallback(uri: string): boolean {
  const prefix = `${NotionOAuthConfig.SCHEME}://${NotionOAuthConfig.HOST}`;
  if (uri.length < prefix.length || uri.substring(0, prefix.length).toLowerCase() !== prefix) {
    return false;
  }
  const next = uri.charAt(prefix.length);
  return next === '' || next === '?' || next === '/' || next === '#';
}

function tokensFrom(response: rcp.Response): NotionOAuthTokens {
  const status = response.statusCode;
  // A throttle is not a bad credential: keep the pair and let sync retry later.
  if (status === 429) {
    throw new NotionError(NotionFailure.RateLimited, status);
  }
  // Notion answers a spent or revoked code / refresh token with 400 invalid_grant: sign in again.
  if (status >= 400 && status < 500) {
    throw new NotionError(NotionFailure.Unauthorized, status);
  }
  if (status < 200 || status >= 300) {
    throw new NotionError(NotionFailure.Unavailable, status);
  }
  let access = '';
  let refresh = '';
  let workspace = '';
  try {
    const parsed = JSON.parse(response.toString() ?? '') as TokenResponse;
    access = typeof parsed.access_token === 'string' ? parsed.access_token : '';
    refresh = typeof parsed.refresh_token === 'string' ? parsed.refresh_token : '';
    workspace = typeof parsed.workspace_name === 'string' ? parsed.workspace_name : '';
  } catch (e) {
    throw new NotionError(NotionFailure.InvalidResponse, status);
  }
  if (access.length === 0 || refresh.length === 0) {
    throw new NotionError(NotionFailure.InvalidResponse, status);
  }
  return new NotionOAuthTokens(access, refresh, workspace);
}

/** One of the relay's two POST routes; both return Notion's token response unchanged. */
async function post(route: string, body: string): Promise<NotionOAuthTokens> {
  const target = `${NotionOAuthConfig.RELAY}/${NotionOAuthConfig.APP}/${route}`;
  // Tokens only ever travel over https.
  if (!target.startsWith('https://')) {
    throw new NotionError(NotionFailure.Unavailable);
  }
  const headers: rcp.RequestHeaders = {
    'accept': 'application/json',
    'content-type': 'application/json; charset=utf-8',
  };
  // Redirects are not followed: a token request sent on elsewhere is a failure.
  const session = rcp.createSession({
    requestConfiguration: { transfer: { autoRedirect: false, timeout: { connectMs: 20000, transferMs: 30000 } } },
  });
  try {
    let response: rcp.Response;
    try {
      response = await session.fetch(new rcp.Request(target, 'POST', headers, body));
    } catch (e) {
      throw new NotionError(NotionFailure.Unavailable);
    }
    return tokensFrom(response);
  } finally {
    session.close();
  }
}

/**
 * 用 Notion 登录, one sign-in at a time for the whole process: settings starts it and waits, the browser comes
 * back through EntryAbility, which hands the link to deliver. The state lives only in memory, so a callback
 * that outlived its process (or was never asked for) is turned down rather than exchanged.
 */
export class NotionOAuth {
  private static expected: string = '';
  /** The last state a callback used, so the same link delivered twice is not reported as expired. */
  private static spent: string = '';
  private static waiter: ((result: NotionOAuthResult) => void) | null = null;
  private static late: string = '';
  private static pending: NotionCredential | null = null;

  /** The relay's start page for [state]; it redirects through Notion and back to leaf://notion-oauth. */
  static startUrl(state: string): string {
    return `${NotionOAuthConfig.RELAY}/${NotionOAuthConfig.APP}/start?state=${encodeURIComponent(state)}`;
  }

  /**
   * Opens the relay's start page in the browser with a fresh state and resolves with what comes back to
   * deliver. A sign-in still waiting is settled as Cancelled first. Throws NotionError when no browser opens.
   */
  static async start(context: common.UIAbilityContext): Promise<NotionOAuthResult> {
    NotionOAuth.settle(new NotionOAuthResult(NotionOAuthOutcome.Cancelled));
    const state = hex(cryptoFramework.createRandom().generateRandomSync(STATE_BYTES).data);
    NotionOAuth.expected = state;
    NotionOAuth.late = '';
    const result = new Promise<NotionOAuthResult>((resolve: (r: NotionOAuthResult) => void) => {
      NotionOAuth.waiter = resolve;
    });
    // The system browser shows the page: no app claims the relay's domain by App Linking.
    const options: OpenLinkOptions = { appLinkingOnly: false };
    try {
      await context.openLink(NotionOAuth.startUrl(state), options);
    } catch (e) {
      NotionOAuth.expected = '';
      NotionOAuth.waiter = null;
      throw new NotionError(NotionFailure.SignIn, 0, NO_BROWSER);
    }
    return result;
  }

  /**
   * Takes the link EntryAbility was opened with (want.uri, from onCreate and onNewWant). Returns false for
   * anything that is not leaf://notion-oauth, which is left alone. A state is good for one callback only.
   */
  static deliver(uri?: string): boolean {
    if (uri === undefined || !isCallback(uri)) {
      return false;
    }
    let params: url.URLParams;
    try {
      params = url.URL.parseURL(uri).params;
    } catch (e) {
      return false;
    }
    const state = params.get('state') ?? '';
    const error = params.get('error') ?? '';
    const code = params.get('code') ?? '';
    const expected = NotionOAuth.expected;
    if (expected.length === 0 && state.length > 0 && sameState(NotionOAuth.spent, state)) {
      return true;
    }
    NotionOAuth.expected = '';
    NotionOAuth.spent = state;
    if (expected.length === 0 || !sameState(expected, state)) {
      NotionOAuth.settle(new NotionOAuthResult(NotionOAuthOutcome.Failed, '', EXPIRED));
    } else if (error === 'access_denied') {
      NotionOAuth.settle(new NotionOAuthResult(NotionOAuthOutcome.Cancelled));
    } else if (error.length > 0 || code.length === 0) {
      NotionOAuth.settle(new NotionOAuthResult(NotionOAuthOutcome.Failed, '', NO_CODE));
    } else {
      NotionOAuth.settle(new NotionOAuthResult(NotionOAuthOutcome.Code, code));
    }
    return true;
  }

  /** Stops waiting (settings closed, or 51 gave up in the browser); a callback after this is turned down. */
  static cancel(): void {
    NotionOAuth.expected = '';
    NotionOAuth.settle(new NotionOAuthResult(NotionOAuthOutcome.Cancelled));
  }

  /**
   * A failed callback nobody was waiting for, such as one that arrived after Leaf was closed while the
   * browser was open; empty when there is none. Returns it once, for settings to show.
   */
  static takeMessage(): string {
    const message = NotionOAuth.late;
    NotionOAuth.late = '';
    return message;
  }

  /** Trades the callback's code for a token pair (POST /leaf/token). */
  static exchange(code: string): Promise<NotionOAuthTokens> {
    const body: CodeBody = { code: code };
    return post('token', JSON.stringify(body));
  }

  /** Trades a refresh token for a new pair (POST /leaf/refresh); the old refresh token is spent. */
  static refresh(refreshToken: string): Promise<NotionOAuthTokens> {
    const body: RefreshBody = { refresh_token: refreshToken };
    return post('refresh', JSON.stringify(body));
  }

  /**
   * 用 Notion 登录 up to the database list: opens the browser, waits for the callback and trades the code for
   * a token pair. Resolves with the access token for discover and connect, or '' when 51 cancelled on
   * Notion's page. Throws NotionError, whose userMessage says what happened.
   *
   * The pair stays in memory until connect saves it, so an existing connection keeps working if no
   * database is picked.
   */
  static async signIn(context: common.UIAbilityContext): Promise<string> {
    const result = await NotionOAuth.start(context);
    if (result.outcome === NotionOAuthOutcome.Cancelled) {
      return '';
    }
    if (result.outcome === NotionOAuthOutcome.Failed) {
      throw new NotionError(NotionFailure.SignIn, 0, result.message);
    }
    let tokens: NotionOAuthTokens;
    try {
      tokens = await NotionOAuth.exchange(result.code);
    } catch (e) {
      throw NotionOAuth.exchangeFailure(e as Error);
    }
    NotionOAuth.pending = NotionCredential.oauth(tokens.access, tokens.refresh);
    return tokens.access;
  }

  /** Nothing retries a sign-in by itself, and a code the relay turns down is spent: both mean start again. */
  private static exchangeFailure(e: Error): Error {
    if (!(e instanceof NotionError)) {
      return e;
    }
    if (e.reason === NotionFailure.Unauthorized) {
      return new NotionError(NotionFailure.SignIn, e.status, EXPIRED);
    }
    if (e.reason === NotionFailure.RateLimited || e.reason === NotionFailure.Unavailable) {
      return new NotionError(NotionFailure.SignIn, e.status, OFFLINE);
    }
    return e;
  }

  /** What connect saves for [token]: the pair signIn returned it from, or else a pasted integration token. */
  static credentialFor(token: string): NotionCredential {
    const pending = NotionOAuth.pending;
    return pending !== null && pending.access === token ? pending : NotionCredential.integration(token);
  }

  /** Drops the signed-in pair once connect has saved it. */
  static forget(): void {
    NotionOAuth.pending = null;
  }

  private static settle(result: NotionOAuthResult): void {
    const waiter = NotionOAuth.waiter;
    NotionOAuth.waiter = null;
    if (waiter !== null) {
      waiter(result);
    } else if (result.outcome === NotionOAuthOutcome.Failed) {
      NotionOAuth.late = result.message;
    }
  }
}

/**
 * Keeps an OAuth connection's access token fresh. Notion turns an expired one down with 401; the relay then
 * trades the refresh token for a new pair. Each refresh rotates the refresh token too, so only one runs at a
 * time, and a caller holding a token that was already replaced just gets the new one.
 */
export class NotionAuth {
  private connected: () => boolean;
  private refreshing: Promise<string> | null = null;

  /** [connected] says whether a connection is still saved: a pair refreshed after a disconnect is dropped. */
  constructor(connected: () => boolean) {
    this.connected = connected;
  }

  /** Runs [block] with [token], and once more with a refreshed one if Notion answers 401. */
  async withToken<T>(token: string, block: (token: string) => Promise<T>): Promise<T> {
    try {
      return await block(token);
    } catch (e) {
      if (!(e instanceof NotionError) || e.reason !== NotionFailure.Unauthorized) {
        throw e as Error;
      }
      const fresh = await this.refreshed(token);
      if (fresh.length === 0) {
        throw e as Error;
      }
      return block(fresh);
    }
  }

  /**
   * A token to retry with after [stale] was turned down, or '' when there is none: a pasted integration
   * token cannot be refreshed, and a refresh token Notion no longer takes means signing in again.
   * While a refresh is running, every caller waits for that one.
   */
  refreshed(stale: string): Promise<string> {
    const running = this.refreshing;
    if (running !== null) {
      return running;
    }
    const run = this.refreshOnce(stale);
    this.refreshing = run;
    run.then(() => {
      this.refreshing = null;
    }, () => {
      this.refreshing = null;
    });
    return run;
  }

  private async refreshOnce(stale: string): Promise<string> {
    const current = await Vault.credential();
    if (current === null) {
      return '';
    }
    // Replaced by a refresh or a new sign-in that finished meanwhile.
    if (current.access !== stale) {
      return current.access;
    }
    if (!current.isOAuth()) {
      return '';
    }
    let tokens: NotionOAuthTokens;
    try {
      tokens = await NotionOAuth.refresh(current.refresh);
    } catch (e) {
      if (e instanceof NotionError && e.reason === NotionFailure.Unauthorized) {
        return '';
      }
      throw e as Error;
    }
    const fresh = NotionCredential.oauth(tokens.access, tokens.refresh);
    // Disconnected or signed in again meanwhile: the new pair belongs to nothing current, so this run stops.
    if (!this.connected() || !(await Vault.replace(current, fresh))) {
      return '';
    }
    return fresh.access;
  }
}
