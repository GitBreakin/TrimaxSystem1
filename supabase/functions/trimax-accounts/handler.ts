// trimax-accounts: Supabase sign-ins for TRIMAX Agents and Drivers, without the Supabase Dashboard.
//
//   provision     POST {"action": "provision", "ticket": "<uuid>"}
//                 From the Desktop, right after an admin creates an account (or presses "Create mobile
//                 sign-in"). The ticket comes from trimax_private.issue_provisioning_ticket, which only an
//                 Active Admin can get, over the Desktop's database connection. This function redeems it
//                 (public.trimax_service_claim_provisioning_ticket), creates the Auth user with the Auth
//                 Admin API (email confirmed, a random password nobody knows, app_metadata
//                 trimax_provisioned + trimax_username) and records + links it with the existing
//                 public.trimax_service_finish_provisioning. A retry links the Auth user a cut-off earlier
//                 call made instead of creating a second one.
//
//   set_password  POST {"action": "set_password", "email": "...", "code": "...", "password": "..."}
//                 From the phone ("Set up or reset password"). The code is the one-time setup / reset
//                 code the Desktop emails. public.trimax_service_use_setup_code checks it (single use,
//                 5 wrong tries, expiry, no account discovery); then the password is set with the Auth
//                 Admin API. TRIMAX never stores it.
//
// Deployed with verify_jwt = false: neither caller has a Supabase session; the ticket or the code is the
// authorization. The project's secret key is read from the Edge Function environment and never leaves
// the server.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_BODY_BYTES = 4096;

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

type Rpc = { ok: boolean; status: number; data: Record<string, unknown> };

async function rpc(key: string, fn: string, args: Record<string, unknown>): Promise<Rpc> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: { ...authHeaders(key), "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(args),
  });
  let data: Record<string, unknown> = {};
  try {
    const parsed = await r.json();
    if (parsed && typeof parsed === "object") data = parsed as Record<string, unknown>;
  } catch (_) {
    // empty or not JSON
  }
  return { ok: r.ok, status: r.status, data };
}

/** A password nobody knows; the person chooses their own with the emailed code. */
function randomPassword(): string {
  const bytes = new Uint8Array(36);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_");
}

/** Same rules as the Desktop (letter and number, at least 8), and at most 72 bytes (Supabase Auth's limit). */
export function passwordProblem(password: string): string | null {
  if (password.length < 8) return "The password must be at least 8 characters.";
  if (new TextEncoder().encode(password).length > 72) return "The password is too long (at most 72 characters).";
  if (!/\p{L}/u.test(password) || !/\p{Nd}/u.test(password)) {
    return "The password must contain at least one letter and one number.";
  }
  return null;
}

type Claim = {
  request_id?: number;
  status?: string;
  message?: string;
  username?: string;
  email?: string;
  existing_auth_user_id?: string | null;
  existing_auth_created_by_trimax?: boolean;
  existing_auth_linked_username?: string | null;
};

async function finish(key: string, requestId: number, outcome: string, authUserId: string | null, message: string | null): Promise<Response> {
  const f = await rpc(key, "trimax_service_finish_provisioning", {
    p_request_id: requestId,
    p_outcome: outcome,
    p_auth_user_id: authUserId,
    p_message: message,
  });
  if (!f.ok) {
    // The Auth user (if any) exists; the next "Create mobile sign-in" finds it and links it.
    return fail(502, "The sign-in was created but could not be recorded. Press \"Create mobile sign-in\" again in a few minutes.");
  }
  return json(200, { status: f.data.status ?? outcome, message: f.data.message ?? null });
}

async function provision(key: string, body: Record<string, unknown>): Promise<Response> {
  const ticket = body.ticket;
  if (typeof ticket !== "string" || !UUID.test(ticket)) return fail(400, "Invalid ticket.");

  const c = await rpc(key, "trimax_service_claim_provisioning_ticket", { p_ticket: ticket });
  if (!c.ok) {
    return c.status === 400 || c.status === 403
      ? fail(403, "This request has expired. Try again.")
      : fail(502, "The sign-in service could not reach the database. Try again.");
  }
  const claim = c.data as Claim;
  if (claim.status === "invited" || claim.status === "linked") {
    return json(200, { status: claim.status, message: "This account already has a mobile sign-in." });
  }
  if (claim.status === "failed") return json(200, { status: "failed", message: claim.message ?? "The sign-in could not be created." });
  if (claim.status === "busy") return json(409, { status: "busy", message: claim.message ?? "Try again in a minute." });
  if (claim.status !== "processing" || typeof claim.request_id !== "number" || !claim.username || !claim.email) {
    return fail(502, "The sign-in service got an unexpected answer.");
  }

  if (claim.existing_auth_user_id) {
    if (claim.existing_auth_linked_username && claim.existing_auth_linked_username !== claim.username) {
      return finish(key, claim.request_id, "failed", null,
        "A Supabase sign-in with this email is already linked to another TRIMAX account.");
    }
    if (!claim.existing_auth_created_by_trimax) {
      return finish(key, claim.request_id, "failed", null,
        "A Supabase sign-in with this email already exists (made outside TRIMAX). Open the account and use \"Link mobile sign-in\".");
    }
    // Made by an earlier call that was cut off before it was recorded: link it, do not make a second one.
    return finish(key, claim.request_id, "linked", claim.existing_auth_user_id, null);
  }

  const created = await fetch(`${SUPABASE_URL}/auth/v1/admin/users`, {
    method: "POST",
    headers: { ...authHeaders(key), "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({
      email: claim.email,
      password: randomPassword(),
      email_confirm: true,
      app_metadata: { trimax_provisioned: true, trimax_username: claim.username },
    }),
  });
  let user: { id?: string } = {};
  try {
    user = await created.json() as { id?: string };
  } catch (_) {
    // handled below
  }
  if (!created.ok || !user.id || !UUID.test(user.id)) {
    const message = created.status === 422 || created.status === 400
      ? "Supabase already has a sign-in with this email. Press \"Create mobile sign-in\" again to check it."
      : `Supabase Auth did not create the sign-in (HTTP ${created.status}). Try again.`;
    return finish(key, claim.request_id, "failed", null, message);
  }
  return finish(key, claim.request_id, "invited", user.id, null);
}

async function setPassword(key: string, body: Record<string, unknown>): Promise<Response> {
  const email = typeof body.email === "string" ? body.email.trim() : "";
  const code = typeof body.code === "string" ? body.code.trim() : "";
  const password = typeof body.password === "string" ? body.password : "";
  if (!email || email.length > 254 || !code || code.length > 20) return fail(400, "Enter your email and the code from the email.");
  const problem = passwordProblem(password);
  if (problem) return fail(400, problem);

  const u = await rpc(key, "trimax_service_use_setup_code", { p_email: email, p_code: code });
  if (!u.ok) return fail(502, "The server could not check the code. Try again.");
  if (u.data.ok !== true || typeof u.data.auth_user_id !== "string" || !UUID.test(u.data.auth_user_id)) {
    return fail(400, typeof u.data.message === "string" ? u.data.message : "The code was not accepted.");
  }

  const put = await fetch(`${SUPABASE_URL}/auth/v1/admin/users/${u.data.auth_user_id}`, {
    method: "PUT",
    headers: { ...authHeaders(key), "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ password }),
  });
  if (!put.ok) {
    let reason = "";
    try {
      const err = await put.json() as Record<string, unknown>;
      reason = String(err.msg ?? err.message ?? err.error_description ?? "");
    } catch (_) {
      // no details
    }
    return put.status === 422 || put.status === 400
      ? fail(400, `Supabase did not accept this password${reason ? ": " + reason : ""}. Ask your administrator for a new code.`)
      : fail(502, "Your password was not changed. Ask your administrator for a new code.");
  }
  await put.body?.cancel();
  return json(200, { ok: true, message: "Password set. You can sign in now." });
}

export async function handle(req: Request): Promise<Response> {
  if (req.method !== "POST") return fail(405, "Use POST.");
  const key = serverKey();
  if (!SUPABASE_URL || !key) return fail(500, "The sign-in service is not configured.");
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) return fail(413, "Request too large.");
  const text = await req.text();
  if (text.length > MAX_BODY_BYTES) return fail(413, "Request too large.");
  let body: Record<string, unknown>;
  try {
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
    body = parsed as Record<string, unknown>;
  } catch (_) {
    return fail(400, "Send a JSON object.");
  }
  if (body.action === "provision") return provision(key, body);
  if (body.action === "set_password") return setPassword(key, body);
  return fail(400, "Unknown action.");
}
