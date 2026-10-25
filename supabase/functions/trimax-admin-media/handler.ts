// trimax-admin-media: gives the Desktop admin ONE private photo (duty attendance photo or delivery proof)
// in exchange for a one-time ticket.
//
// The Desktop never holds a storage key. It asks the database (as the database owner, over JDBC) for a
// ticket with trimax_private.issue_media_ticket(admin, bucket, path), then POSTs {"ticket": "<uuid>"} here.
// This function redeems the ticket with public.trimax_service_redeem_media_ticket (service role only:
// the ticket works once, within 2 minutes) and streams that one photo back.
//
// Deployed with verify_jwt = false: the caller has no Supabase sign-in; the ticket is the authorization.
// The project's secret key is read from the Edge Function environment and never leaves the server.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PHOTO_PATH = /^[A-Za-z0-9-]{1,64}\/[A-Za-z0-9_.-]{1,120}\.jpg$/;
const BUCKETS = new Set(["duty-photos", "delivery-proofs"]);
const MAX_BYTES = 6 * 1024 * 1024;

/** The server key: the new secret key ("default") when the project has one, else the legacy service_role key. */
function serverKey(): string {
  const keys = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (keys) {
    try {
      const parsed = JSON.parse(keys) as Record<string, string>;
      if (parsed["default"]) return parsed["default"];
    } catch (_) {
      // fall through to the legacy key
    }
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}

/** New secret keys go in the apikey header only; legacy JWT keys also as Bearer. */
function authHeaders(key: string): Record<string, string> {
  const h: Record<string, string> = { apikey: key };
  if (!key.startsWith("sb_")) h["Authorization"] = `Bearer ${key}`;
  return h;
}

function json(status: number, message: string): Response {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

export async function handle(req: Request): Promise<Response> {
  if (req.method !== "POST") return json(405, "Use POST.");
  const key = serverKey();
  if (!SUPABASE_URL || !key) return json(500, "The photo service is not configured.");

  let ticket: unknown;
  try {
    ticket = (await req.json())?.ticket;
  } catch (_) {
    return json(400, "Send {\"ticket\": \"...\"}.");
  }
  if (typeof ticket !== "string" || !UUID.test(ticket)) return json(400, "Invalid ticket.");

  // 1. Use the ticket (once).
  const redeem = await fetch(`${SUPABASE_URL}/rest/v1/rpc/trimax_service_redeem_media_ticket`, {
    method: "POST",
    headers: { ...authHeaders(key), "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ p_ticket: ticket }),
  });
  if (!redeem.ok) {
    await redeem.body?.cancel();
    return json(redeem.status === 400 || redeem.status === 403 ? 403 : 502,
      redeem.status === 400 || redeem.status === 403
        ? "This photo link has expired. Open the photo again."
        : "The photo service could not check the ticket.");
  }
  const target = await redeem.json() as { bucket?: string; path?: string };
  if (!target.bucket || !BUCKETS.has(target.bucket) || !target.path || !PHOTO_PATH.test(target.path)) {
    return json(502, "The photo service got an unexpected answer.");
  }

  // 2. Download that one photo and pass it on.
  const object = await fetch(
    `${SUPABASE_URL}/storage/v1/object/${target.bucket}/${target.path.split("/").map(encodeURIComponent).join("/")}`,
    { headers: authHeaders(key) },
  );
  if (!object.ok) {
    await object.body?.cancel();
    return json(object.status === 400 || object.status === 404 ? 404 : 502,
      object.status === 400 || object.status === 404 ? "That photo is no longer on the server." : "Storage did not answer.");
  }
  const bytes = new Uint8Array(await object.arrayBuffer());
  if (bytes.length === 0 || bytes.length > MAX_BYTES) return json(502, "The photo file is empty or too large.");
  return new Response(bytes, {
    status: 200,
    headers: { "Content-Type": "image/jpeg", "Content-Length": String(bytes.length), "Cache-Control": "no-store" },
  });
}

