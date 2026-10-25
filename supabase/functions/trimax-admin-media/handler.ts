// trimax-admin-media: the Desktop admin's only way to private photos and to product pictures, one file
// per one-time ticket.
//
// The Desktop never holds a storage key. It asks the database (as the database owner, over JDBC) for a
// ticket (trimax_private.issue_media_ticket for a duty photo / delivery proof, or
// trimax_private.issue_product_image_ticket for a product picture). This function redeems the ticket
// with public.trimax_service_redeem_media_ticket (service role only; one use, within 2 minutes), which
// says which file and what for:
//
//   view    POST {"ticket": "<uuid>"}                       -> the photo's bytes (duty-photos, delivery-proofs)
//   upload  POST <JPEG bytes>, header x-trimax-ticket: <uuid> -> stores the product picture at the ticket's path
//   delete  POST {"ticket": "<uuid>"}                       -> removes that product picture
//
// Deployed with verify_jwt = false: the caller has no Supabase sign-in; the ticket is the authorization.
// The project's secret key is read from the Edge Function environment and never leaves the server.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PHOTO_PATH = /^[A-Za-z0-9-]{1,64}\/[A-Za-z0-9_.-]{1,120}\.jpg$/;
const PRODUCT_PATH = /^products\/[0-9]{1,9}\/[A-Za-z0-9_-]{1,80}\.jpg$/;
const VIEW_BUCKETS = new Set(["duty-photos", "delivery-proofs"]);
const PRODUCT_BUCKET = "product-images";
const MAX_VIEW_BYTES = 6 * 1024 * 1024;
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

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

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function fail(status: number, message: string): Response {
  return json(status, { error: message });
}

function objectUrl(bucket: string, path: string): string {
  return `${SUPABASE_URL}/storage/v1/object/${bucket}/${path.split("/").map(encodeURIComponent).join("/")}`;
}

type Target = { bucket: string; path: string; action: string };

/** Uses the ticket (once). Returns what it is for, or an error response. */
async function redeem(key: string, ticket: string): Promise<Target | Response> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/trimax_service_redeem_media_ticket`, {
    method: "POST",
    headers: { ...authHeaders(key), "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ p_ticket: ticket }),
  });
  if (!r.ok) {
    await r.body?.cancel();
    return r.status === 400 || r.status === 403
      ? fail(403, "This link has expired. Try again.")
      : fail(502, "The photo service could not check the ticket.");
  }
  const t = await r.json() as { bucket?: string; path?: string; action?: string };
  if (!t.bucket || !t.path) return fail(502, "The photo service got an unexpected answer.");
  return { bucket: t.bucket, path: t.path, action: t.action ?? "view" };
}

/** A real JPEG: starts with FF D8 FF. */
function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

async function upload(req: Request, key: string): Promise<Response> {
  const ticket = req.headers.get("x-trimax-ticket") ?? "";
  if (!UUID.test(ticket)) return fail(400, "Invalid ticket.");
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > MAX_UPLOAD_BYTES) return fail(413, "The picture is larger than 2 MB.");
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length === 0) return fail(400, "The picture is empty.");
  if (bytes.length > MAX_UPLOAD_BYTES) return fail(413, "The picture is larger than 2 MB.");
  if (!isJpeg(bytes)) return fail(415, "Only JPEG pictures are accepted.");

  const t = await redeem(key, ticket);
  if (t instanceof Response) return t;
  if (t.action !== "upload" || t.bucket !== PRODUCT_BUCKET || !PRODUCT_PATH.test(t.path)) {
    return fail(403, "This ticket is not for uploading a product picture.");
  }
  const put = await fetch(objectUrl(t.bucket, t.path), {
    method: "POST",
    headers: {
      ...authHeaders(key),
      "Content-Type": "image/jpeg",
      "x-upsert": "false",
      // Every upload has a new random name, so the CDN may keep it for a year.
      "cache-control": "max-age=31536000",
    },
    body: bytes,
  });
  if (!put.ok) {
    const text = (await put.text()).toLowerCase();
    if (text.includes("payload too large") || put.status === 413) return fail(413, "The picture is larger than 2 MB.");
    return fail(502, "Storage did not accept the picture. Try again.");
  }
  await put.body?.cancel();
  return json(200, { path: t.path, bytes: bytes.length });
}

async function viewOrDelete(req: Request, key: string): Promise<Response> {
  let ticket: unknown;
  try {
    ticket = (await req.json())?.ticket;
  } catch (_) {
    return fail(400, "Send {\"ticket\": \"...\"}.");
  }
  if (typeof ticket !== "string" || !UUID.test(ticket)) return fail(400, "Invalid ticket.");

  const t = await redeem(key, ticket);
  if (t instanceof Response) return t;

  if (t.action === "delete") {
    if (t.bucket !== PRODUCT_BUCKET || !PRODUCT_PATH.test(t.path)) return fail(403, "Not a product picture.");
    const del = await fetch(objectUrl(t.bucket, t.path), { method: "DELETE", headers: authHeaders(key) });
    await del.body?.cancel();
    // Already gone counts as deleted.
    if (!del.ok && del.status !== 400 && del.status !== 404) return fail(502, "Storage did not delete the picture.");
    return json(200, { deleted: t.path });
  }

  if (t.action !== "view" || !VIEW_BUCKETS.has(t.bucket) || !PHOTO_PATH.test(t.path)) {
    return fail(502, "The photo service got an unexpected answer.");
  }
  const object = await fetch(objectUrl(t.bucket, t.path), { headers: authHeaders(key) });
  if (!object.ok) {
    await object.body?.cancel();
    return object.status === 400 || object.status === 404
      ? fail(404, "That photo is no longer on the server.")
      : fail(502, "Storage did not answer.");
  }
  const bytes = new Uint8Array(await object.arrayBuffer());
  if (bytes.length === 0 || bytes.length > MAX_VIEW_BYTES) return fail(502, "The photo file is empty or too large.");
  return new Response(bytes, {
    status: 200,
    headers: { "Content-Type": "image/jpeg", "Content-Length": String(bytes.length), "Cache-Control": "no-store" },
  });
}

export async function handle(req: Request): Promise<Response> {
  if (req.method !== "POST") return fail(405, "Use POST.");
  const key = serverKey();
  if (!SUPABASE_URL || !key) return fail(500, "The photo service is not configured.");
  const type = (req.headers.get("content-type") ?? "").toLowerCase();
  if (type.startsWith("image/")) return upload(req, key);
  return viewOrDelete(req, key);
}
