// TRIMAX mobile account provisioning: the part that talks to Supabase. Runs inside the Edge Function
// (index.ts, Deno) and in the local tests (Node). Uses fetch only, so there are no dependencies.
//
// For every open request in trimax_private.provisioning_requests (queued by the desktop):
//   * no Supabase Auth user for the email -> create one (unconfirmed, no password) whose app_metadata
//     says TRIMAX created it for this username, send the invitation email, and link it;
//   * an Auth user TRIMAX created for this username already exists -> resend the invitation if it was
//     never accepted, otherwise send a password-setup (recovery) email; link it;
//   * any other existing Auth user for that email -> do NOT link it (it may belong to someone else);
//     the desktop shows why, and an administrator decides.
// The database re-checks every rule in trimax_service_finish_provisioning before it links anything.
// Nothing here logs or returns passwords, tokens or keys.

export interface Config {
    /** https://<project-ref>.supabase.co */
    supabaseUrl: string;
    /** Service role key (legacy JWT) or a secret key (sb_secret_...). Server-side only. */
    serviceRoleKey: string;
    /** Where the invitation / password email link sends the person, e.g. trimax://auth-callback */
    redirectUrl: string;
    /** Requests handled per run (1..25). */
    batchSize?: number;
}

export interface Logger {
    info(message: string, fields?: Record<string, unknown>): void;
    error(message: string, fields?: Record<string, unknown>): void;
}

export type FetchFn = (url: string, init?: RequestInit) => Promise<Response>;

export interface Claimed {
    request_id: number;
    username: string;
    email: string;
    existing_auth_user_id: string | null;
    existing_auth_confirmed: boolean | null;
    existing_auth_created_by_trimax: boolean;
    existing_auth_linked_username: string | null;
}

export interface Result {
    request_id: number;
    username: string;
    status: string;
    message?: string;
}

/** Error from Supabase with a message that is safe to store and show (no secrets). */
export class SupabaseError extends Error {
    status: number;
    code: string;
    constructor(status: number, code: string, message: string) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

export const NOT_CREATED_BY_TRIMAX =
    'A Supabase sign-in already exists for this email and was not created by TRIMAX. Make sure it belongs to ' +
    'this person, then use "Link mobile sign-in" on the desktop - or delete it in Supabase and send the invitation again.';

export function checkConfig(cfg: Config): void {
    if (!/^https:\/\/[^/]+$/.test(cfg.supabaseUrl) && !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(cfg.supabaseUrl)) {
        throw new Error('SUPABASE_URL must be https://<project-ref>.supabase.co');
    }
    if (!cfg.serviceRoleKey || cfg.serviceRoleKey.startsWith('sb_publishable_')) {
        throw new Error('A service role / secret key is required (never the publishable key).');
    }
    if (!/^[a-z][a-z0-9+.-]*:\/\/[^\s]+$/i.test(cfg.redirectUrl)) {
        throw new Error('TRIMAX_INVITE_REDIRECT_URL must be a URL such as trimax://auth-callback');
    }
}

function headers(cfg: Config): Record<string, string> {
    const h: Record<string, string> = { apikey: cfg.serviceRoleKey, 'Content-Type': 'application/json' };
    // Legacy service_role keys are JWTs and may also go in Authorization. New secret keys must not.
    if (cfg.serviceRoleKey.startsWith('eyJ')) h.Authorization = `Bearer ${cfg.serviceRoleKey}`;
    return h;
}

async function call(cfg: Config, fetchFn: FetchFn, method: string, path: string, body: unknown): Promise<unknown> {
    const res = await fetchFn(cfg.supabaseUrl + path, {
        method,
        headers: headers(cfg),
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json: unknown = null;
    try {
        json = text ? JSON.parse(text) : null;
    } catch {
        json = null;
    }
    if (!res.ok) {
        const o = (json ?? {}) as Record<string, unknown>;
        const code = String(o.error_code ?? o.code ?? res.status);
        const msg = String(o.msg ?? o.message ?? o.error_description ?? `HTTP ${res.status}`);
        throw new SupabaseError(res.status, code, msg.slice(0, 200));
    }
    return json;
}

function rpc(cfg: Config, fetchFn: FetchFn, fn: string, args: Record<string, unknown>): Promise<unknown> {
    return call(cfg, fetchFn, 'POST', `/rest/v1/rpc/${fn}`, args);
}

function withRedirect(cfg: Config, path: string): string {
    return `${path}?redirect_to=${encodeURIComponent(cfg.redirectUrl)}`;
}

async function finish(cfg: Config, fetchFn: FetchFn, r: Claimed, outcome: string, authUserId: string | null,
                      message?: string): Promise<Result> {
    const out = (await rpc(cfg, fetchFn, 'trimax_service_finish_provisioning', {
        p_request_id: r.request_id,
        p_outcome: outcome,
        p_auth_user_id: authUserId,
        p_message: message ?? null,
    })) as Record<string, unknown>;
    return {
        request_id: r.request_id,
        username: r.username,
        status: String(out?.status ?? 'unknown'),
        message: out?.message ? String(out.message) : undefined,
    };
}

/** Handles one claimed request. Throws only for unexpected failures (the caller marks the request failed). */
export async function provisionOne(cfg: Config, fetchFn: FetchFn, r: Claimed): Promise<Result> {
    if (r.existing_auth_user_id) {
        if (!r.existing_auth_created_by_trimax) {
            return finish(cfg, fetchFn, r, 'failed', null, NOT_CREATED_BY_TRIMAX);
        }
        if (r.existing_auth_linked_username && r.existing_auth_linked_username !== r.username) {
            return finish(cfg, fetchFn, r, 'failed', null, 'That Supabase sign-in is linked to another TRIMAX account.');
        }
        if (!r.existing_auth_confirmed) {
            // Never accepted: Supabase resends the invitation for an unconfirmed user.
            await call(cfg, fetchFn, 'POST', withRedirect(cfg, '/auth/v1/invite'), { email: r.email });
            return finish(cfg, fetchFn, r, 'invited', r.existing_auth_user_id);
        }
        // Accepted earlier (for example the link was removed and is being restored): password-setup email.
        await call(cfg, fetchFn, 'POST', withRedirect(cfg, '/auth/v1/recover'), { email: r.email });
        return finish(cfg, fetchFn, r, 'linked', r.existing_auth_user_id);
    }

    // Create the user first with app_metadata (only the service role can set it), so the database can
    // later tell that TRIMAX created it for exactly this account; then send the invitation.
    const user = (await call(cfg, fetchFn, 'POST', '/auth/v1/admin/users', {
        email: r.email,
        email_confirm: false,
        app_metadata: { trimax_provisioned: true, trimax_username: r.username },
    })) as Record<string, unknown>;
    const id = typeof user?.id === 'string' ? user.id : null;
    if (!id) throw new SupabaseError(500, 'bad_response', 'Supabase did not return the new user id.');
    try {
        await call(cfg, fetchFn, 'POST', withRedirect(cfg, '/auth/v1/invite'), { email: r.email });
    } catch (e) {
        const why = e instanceof SupabaseError ? `${e.code}: ${e.message}` : 'unknown error';
        return finish(cfg, fetchFn, r, 'failed', id,
            `The sign-in was created but the invitation email could not be sent (${why}). Send the invitation again.`);
    }
    return finish(cfg, fetchFn, r, 'invited', id);
}

/** Claims open requests and handles them one by one. Returns a summary without emails or secrets. */
export async function processQueue(cfg: Config, fetchFn: FetchFn, log: Logger): Promise<Result[]> {
    checkConfig(cfg);
    const batch = Math.max(1, Math.min(cfg.batchSize ?? 10, 25));
    const claimed = ((await rpc(cfg, fetchFn, 'trimax_service_claim_provisioning', { p_limit: batch })) ?? []) as Claimed[];
    const results: Result[] = [];
    for (const r of claimed) {
        try {
            const res = await provisionOne(cfg, fetchFn, r);
            log.info('provisioning request handled', { request_id: r.request_id, username: r.username, status: res.status });
            results.push(res);
        } catch (e) {
            const why = e instanceof SupabaseError ? `${e.code}: ${e.message}` : 'unexpected error';
            log.error('provisioning request failed', { request_id: r.request_id, username: r.username, reason: why });
            try {
                results.push(await finish(cfg, fetchFn, r, 'failed', null, `Could not provision the sign-in (${why}).`));
            } catch {
                // The claim expires after 10 minutes and the request is retried (at most 5 attempts).
                results.push({ request_id: r.request_id, username: r.username, status: 'processing', message: why });
            }
        }
    }
    return results;
}

/** Constant-time comparison for the shared secret header. */
export function timingSafeEqual(a: string, b: string): boolean {
    const enc = new TextEncoder();
    const x = enc.encode(a);
    const y = enc.encode(b);
    let diff = x.length ^ y.length;
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
        diff |= (x[i % Math.max(x.length, 1)] ?? 0) ^ (y[i % Math.max(y.length, 1)] ?? 0);
    }
    return diff === 0 && x.length === y.length;
}
