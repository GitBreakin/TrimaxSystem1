// TEST ONLY. A small stand-in for Supabase on a DISPOSABLE local PostgreSQL that has the TRIMAX schema
// (sql/tests/rebuild_check.sh-style build + sql/tests/00_supabase_emulation.sql roles), so the REAL
// handler.ts, the Desktop and the phone client can be tried end to end without touching the live project:
//
//   /functions/v1/trimax-accounts      -> handler.ts (this folder)
//   /rest/v1/rpc/<fn>                  -> select public.<fn>(...) as service_role / authenticated / anon,
//                                         with request.jwt.claims set like PostgREST does
//   /auth/v1/admin/users[/<id>]        -> insert / update auth.users (service key only)
//   /auth/v1/token?grant_type=password -> checks auth.users (password kept as sha256 here, test only)
//   PUT /auth/v1/user                  -> the signed-in user changes their password / user_metadata
//
// Run:  PGDATABASE=trimax_demo PORT=54400 node --experimental-strip-types local_supabase.mjs
// Never point it at Supabase. Control: POST /__test/fail-next-finish makes the next finish call fail once.
import http from "node:http";
import { spawnSync } from "node:child_process";

const PORT = Number(process.env.PORT ?? 54400);
const SECRET = "sb_secret_local_test";
const PUBLISHABLE = "sb_publishable_test";
const PG = ["-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
  "-h", process.env.PGHOST ?? "127.0.0.1", "-p", process.env.PGPORT ?? "54329",
  "-U", process.env.PGUSER ?? "postgres", "-d", process.env.PGDATABASE ?? "trimax_demo"];
if ((process.env.PGDATABASE ?? "trimax_demo").startsWith("postgres")) throw new Error("use a disposable test database");

const env = { SUPABASE_URL: `http://127.0.0.1:${PORT}`, SUPABASE_SECRET_KEYS: JSON.stringify({ default: SECRET }) };
globalThis.Deno = { env: { get: (k) => env[k] } };
const { handle } = await import("./handler.ts");

let failNextFinish = false;
export const calls = [];

function lit(v) {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  if (typeof v === "object") return `'${JSON.stringify(v).replaceAll("'", "''")}'::jsonb`;
  return `'${String(v).replaceAll("'", "''")}'`;
}

function sql(text) {
  const r = spawnSync("psql", PG, { input: text, encoding: "utf8" });
  if (r.status === 0) return { ok: true, out: r.stdout.trim().split("\n").filter(Boolean).pop() ?? "" };
  const m = /ERROR:\s+([0-9A-Z]{5}):\s+(.*)/.exec(r.stderr);
  return { ok: false, code: m ? m[1] : "XX000", message: m ? m[2] : r.stderr.trim() };
}

function send(res, status, obj) {
  const text = typeof obj === "string" ? obj : JSON.stringify(obj);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(text);
}

function caller(req) {
  const key = req.headers["apikey"];
  const auth = req.headers["authorization"] ?? "";
  if (key === SECRET) return { role: "service_role", claims: { role: "service_role" } };
  const m = /^Bearer fake-token\.([0-9a-f-]{36})$/.exec(auth);
  if (m) return { role: "authenticated", claims: { sub: m[1], role: "authenticated" } };
  return { role: "anon", claims: { role: "anon" } };
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", async () => {
    const raw = Buffer.concat(chunks).toString("utf8");
    const url = new URL(req.url, env.SUPABASE_URL);
    calls.push({ method: req.method, path: url.pathname, apikey: req.headers["apikey"] ?? null, auth: req.headers["authorization"] ?? null });
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch (_) { body = {}; }

    if (url.pathname === "/__test/fail-next-finish") { failNextFinish = true; return send(res, 200, { ok: true }); }

    if (url.pathname === "/functions/v1/trimax-accounts") {
      const r = await handle(new Request(env.SUPABASE_URL + url.pathname, {
        method: req.method, headers: { "content-type": req.headers["content-type"] ?? "application/json" },
        body: req.method === "GET" ? undefined : raw,
      }));
      res.writeHead(r.status, { "Content-Type": r.headers.get("content-type") ?? "application/json" });
      return res.end(Buffer.from(await r.arrayBuffer()));
    }

    const rpc = /^\/rest\/v1\/rpc\/([a-z_][a-z0-9_]*)$/.exec(url.pathname);
    if (rpc && req.method === "POST") {
      const fn = rpc[1];
      if (fn === "trimax_service_finish_provisioning" && failNextFinish) {
        failNextFinish = false;
        return send(res, 503, { message: "simulated outage" });
      }
      const who = caller(req);
      const args = Object.entries(body).map(([k, v]) => `${k} => ${lit(v)}`).join(", ");
      const r = sql(`begin;\nset local role ${who.role};\nset local "request.jwt.claims" to ${lit(JSON.stringify(who.claims))};\n`
        + `select coalesce(to_jsonb(public.${fn}(${args})), 'null'::jsonb);\ncommit;\n`);
      if (r.ok) return send(res, 200, r.out);
      const status = r.code === "42501" ? (who.role === "anon" ? 401 : 403) : r.code.startsWith("TM") || r.code === "P0001" ? 400 : 500;
      return send(res, status, { code: r.code, message: r.message });
    }

    if (url.pathname === "/auth/v1/admin/users" && req.method === "POST") {
      if (req.headers["apikey"] !== SECRET) return send(res, 401, { msg: "service key required" });
      const r = sql(`insert into auth.users (email, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, encrypted_password) values (`
        + `lower(${lit(body.email)}), case when ${lit(!!body.email_confirm)} then now() end, coalesce(${lit(body.app_metadata ?? {})}, '{}'), `
        + `coalesce(${lit(body.user_metadata ?? {})}, '{}'), `
        + `encode(sha256(convert_to(${lit(body.password ?? "")}, 'UTF8')), 'hex')) returning json_build_object('id', id, 'email', email);`);
      if (r.ok) return send(res, 200, r.out);
      if (r.code === "23505") return send(res, 422, { code: "email_exists", msg: "A user with this email address has already been registered" });
      return send(res, 500, { msg: r.message });
    }
    const one = /^\/auth\/v1\/admin\/users\/([0-9a-f-]{36})$/.exec(url.pathname);
    if (one && req.method === "PUT") {
      if (req.headers["apikey"] !== SECRET) return send(res, 401, { msg: "service key required" });
      const r = sql(`update auth.users set `
        + (body.password ? `encrypted_password = encode(sha256(convert_to(${lit(body.password)}, 'UTF8')), 'hex'), ` : "")
        + `raw_user_meta_data = raw_user_meta_data || coalesce(${lit(body.user_metadata ?? {})}, '{}') `
        + `where id = ${lit(one[1])} returning json_build_object('id', id);`);
      if (r.ok && r.out) return send(res, 200, r.out);
      return send(res, 404, { msg: "User not found" });
    }
    if (url.pathname === "/auth/v1/token" && url.searchParams.get("grant_type") === "password") {
      if (req.headers["apikey"] !== PUBLISHABLE) return send(res, 401, { msg: "bad key" });
      const r = sql(`select json_build_object('id', id, 'email', email, 'user_metadata', raw_user_meta_data) from auth.users where lower(email) = lower(${lit(body.email ?? "")}) `
        + `and email_confirmed_at is not null and encrypted_password = encode(sha256(convert_to(${lit(body.password ?? "")}, 'UTF8')), 'hex');`);
      if (!r.ok || !r.out) return send(res, 400, { code: "invalid_credentials", msg: "Invalid login credentials" });
      const user = JSON.parse(r.out);
      return send(res, 200, { access_token: `fake-token.${user.id}`, token_type: "bearer", expires_in: 3600, refresh_token: "fake-refresh", user });
    }
    if (url.pathname === "/auth/v1/user" && req.method === "PUT") {
      const who = caller(req);
      if (who.role !== "authenticated") return send(res, 401, { msg: "sign in first" });
      const r = sql(`update auth.users set `
        + (body.password ? `encrypted_password = encode(sha256(convert_to(${lit(body.password)}, 'UTF8')), 'hex'), ` : "")
        + `raw_user_meta_data = raw_user_meta_data || coalesce(${lit(body.data ?? {})}, '{}') `
        + `where id = ${lit(who.claims.sub)} returning json_build_object('id', id, 'email', email);`);
      return r.ok && r.out ? send(res, 200, r.out) : send(res, 400, { msg: "update failed" });
    }
    if (url.pathname === "/auth/v1/logout") { res.writeHead(204); return res.end(); }
    send(res, 404, { msg: "not found in the local stand-in" });
  });
});

server.listen(PORT, "127.0.0.1", () => console.log(`local Supabase stand-in on ${env.SUPABASE_URL}`));
