// Supabase Edge Function: provision-mobile-accounts
// Processes the desktop's "Send mobile invite" queue (see provision.ts and README.md).
// Deploy with --no-verify-jwt: callers authenticate with the x-trimax-provisioning-secret header,
// which only the database (Vault) and the administrator know. The service-role key never leaves Supabase.
import { processQueue, timingSafeEqual } from './provision.ts';

function json(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const log = {
    info: (message: string, fields?: Record<string, unknown>) => console.log(JSON.stringify({ level: 'info', message, ...fields })),
    error: (message: string, fields?: Record<string, unknown>) => console.error(JSON.stringify({ level: 'error', message, ...fields })),
};

Deno.serve(async (req: Request) => {
    if (req.method !== 'POST') return json(405, { error: 'Use POST.' });

    const expected = Deno.env.get('TRIMAX_PROVISIONING_SECRET') ?? '';
    if (expected.length < 32) {
        log.error('TRIMAX_PROVISIONING_SECRET is missing or shorter than 32 characters; refusing to run.');
        return json(500, { error: 'Function is not configured.' });
    }
    const given = req.headers.get('x-trimax-provisioning-secret') ?? '';
    if (!timingSafeEqual(given, expected)) return json(401, { error: 'Unauthorized.' });

    try {
        const results = await processQueue({
            supabaseUrl: (Deno.env.get('SUPABASE_URL') ?? '').replace(/\/+$/, ''),
            serviceRoleKey: Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '',
            redirectUrl: Deno.env.get('TRIMAX_INVITE_REDIRECT_URL') ?? 'trimax://auth-callback',
        }, fetch, log);
        return json(200, { processed: results.length, results });
    } catch (e) {
        log.error('provisioning run failed', { reason: e instanceof Error ? e.message.slice(0, 200) : 'unknown' });
        return json(500, { error: 'Provisioning run failed. See the function logs.' });
    }
});
