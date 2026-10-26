// Tests for handler.ts against a fake Supabase (PostgREST RPC + Auth Admin API).
// Run: node --experimental-strip-types supabase/functions/trimax-accounts/test_handler.mjs
import http from "node:http";
const env = {};
globalThis.Deno = { env: { get: (k) => env[k] } };

const seen = [];
let claim = {};             // what trimax_service_claim_provisioning_ticket answers
let claimStatus = 200;
let createStatus = 200;
let finishStatus = 200;
let codeAnswer = {};
let putStatus = 200;
const NEW_ID = "11111111-2222-4333-8444-555555555555";

const server = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const entry = { method: req.method, url: req.url, apikey: req.headers["apikey"], auth: req.headers["authorization"], body };
    seen.push(entry);
    const send = (status, obj) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(obj)); };
    if (req.url === "/rest/v1/rpc/trimax_service_claim_provisioning_ticket") return send(claimStatus, claimStatus === 200 ? claim : { code: "TM403", message: "expired" });
    if (req.url === "/rest/v1/rpc/trimax_service_finish_provisioning") {
      const a = JSON.parse(body);
      return send(finishStatus, { request_id: a.p_request_id, status: a.p_outcome === "failed" ? "failed" : a.p_outcome, message: a.p_message ?? undefined });
    }
    if (req.url === "/auth/v1/admin/users" && req.method === "POST") {
      return createStatus === 200 ? send(200, { id: NEW_ID, email: JSON.parse(body).email }) : send(createStatus, { code: "email_exists", msg: "already registered" });
    }
    if (req.url === "/rest/v1/rpc/trimax_service_use_setup_code") return send(200, codeAnswer);
    if (req.url.startsWith("/auth/v1/admin/users/") && req.method === "PUT") {
      return putStatus === 200 ? send(200, { id: req.url.split("/").pop() }) : send(putStatus, { msg: "Password is known to be weak" });
    }
    res.writeHead(404); res.end();
  });
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
env.SUPABASE_URL = `http://127.0.0.1:${server.address().port}`;
env.SUPABASE_SECRET_KEYS = JSON.stringify({ default: "sb_secret_test" });
const { handle, passwordProblem } = await import("./handler.ts");

let fails = 0;
const check = (ok, label) => { console.log((ok ? "PASS " : "FAIL ") + label); if (!ok) fails++; };
const post = (obj) => new Request("http://edge/trimax-accounts", { method: "POST", body: typeof obj === "string" ? obj : JSON.stringify(obj), headers: { "Content-Type": "application/json" } });
const T = "3f2b8c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f";
const AUTH = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const urls = () => seen.map((s) => s.method + " " + s.url);
const reset = () => { seen.length = 0; claimStatus = 200; createStatus = 200; finishStatus = 200; putStatus = 200; };

// ---- request shape
let r = await handle(new Request("http://edge/x", { method: "GET" }));
check(r.status === 405, "GET is refused");
r = await handle(post("not json"));
check(r.status === 400, "a body that is not JSON is refused");
r = await handle(post({ action: "delete_everyone" }));
check(r.status === 400 && seen.length === 0, "an unknown action is refused without touching Supabase");
r = await handle(post("x".repeat(5000)));
check(r.status === 413, "a large body is refused");

// ---- provision
reset();
r = await handle(post({ action: "provision", ticket: "not-a-uuid" }));
check(r.status === 400 && seen.length === 0, "provision needs a ticket that looks like one");

reset(); claimStatus = 400;
r = await handle(post({ action: "provision", ticket: T }));
check(r.status === 403 && !urls().some((u) => u.includes("/auth/")), "an expired / used ticket creates nothing");

reset(); claim = { request_id: 7, status: "processing", username: "agent_tim", email: "tim@example.test", existing_auth_user_id: null };
r = await handle(post({ action: "provision", ticket: T }));
let b = await r.json();
const create = seen.find((s) => s.url === "/auth/v1/admin/users");
const cbody = create ? JSON.parse(create.body) : {};
check(r.status === 200 && b.status === "invited", "new account: Auth user created and recorded as invited");
check(cbody.email === "tim@example.test" && cbody.email_confirm === true && typeof cbody.password === "string" && cbody.password.length >= 40,
  "Auth user: the queued email, confirmed, with a long random password");
check(cbody.app_metadata?.trimax_provisioned === true && cbody.app_metadata?.trimax_username === "agent_tim",
  "Auth user carries app_metadata trimax_provisioned + trimax_username (what finish checks)");
const fin = seen.find((s) => s.url.endsWith("trimax_service_finish_provisioning"));
check(fin && JSON.parse(fin.body).p_auth_user_id === NEW_ID && JSON.parse(fin.body).p_request_id === 7, "finish gets the new user id and the request");
check(create.apikey === "sb_secret_test" && !create.auth, "new secret key is sent as apikey only");
check(!JSON.stringify(b).includes(cbody.password), "the random password is never returned");

reset(); claim = { request_id: 8, status: "processing", username: "driver_ana", email: "ana@example.test",
  existing_auth_user_id: AUTH, existing_auth_created_by_trimax: true, existing_auth_linked_username: null };
r = await handle(post({ action: "provision", ticket: T }));
b = await r.json();
check(r.status === 200 && b.status === "linked" && !urls().includes("POST /auth/v1/admin/users"),
  "retry after a cut-off call: links the existing TRIMAX-made user, creates no second one");

reset(); claim = { request_id: 9, status: "processing", username: "driver_ana", email: "ana@example.test",
  existing_auth_user_id: AUTH, existing_auth_created_by_trimax: false, existing_auth_linked_username: null };
r = await handle(post({ action: "provision", ticket: T }));
b = await r.json();
check(b.status === "failed" && /Link mobile sign-in/.test(b.message) && !urls().includes("POST /auth/v1/admin/users"),
  "a hand-made sign-in with that email: not taken over, admin told to use Link mobile sign-in");

reset(); claim = { request_id: 10, status: "processing", username: "agent_x", email: "x@example.test",
  existing_auth_user_id: AUTH, existing_auth_created_by_trimax: true, existing_auth_linked_username: "agent_other" };
r = await handle(post({ action: "provision", ticket: T }));
b = await r.json();
check(b.status === "failed" && /another TRIMAX account/.test(b.message), "a sign-in linked to someone else is refused");

reset(); claim = { request_id: 11, status: "processing", username: "agent_y", email: "y@example.test", existing_auth_user_id: null }; createStatus = 422;
r = await handle(post({ action: "provision", ticket: T }));
b = await r.json();
check(b.status === "failed" && /already has a sign-in/.test(b.message), "Auth says the email exists (race): recorded as failed with a clear message");

reset(); claim = { request_id: 12, status: "processing", username: "agent_z", email: "z@example.test", existing_auth_user_id: null }; finishStatus = 500;
r = await handle(post({ action: "provision", ticket: T }));
b = await r.json();
check(r.status === 502 && /again/.test(b.error), "user created but not recorded: 502 and the admin is told to press again (the retry links it)");

for (const [status, label] of [["busy", "busy"], ["failed", "failed"], ["linked", "already linked"]]) {
  reset(); claim = { request_id: 13, status, message: "m" };
  r = await handle(post({ action: "provision", ticket: T }));
  check(!urls().some((u) => u.includes("/auth/")), `claim says ${label}: nothing is created`);
}

// ---- set_password
check(passwordProblem("short1") !== null, "password rule: at least 8");
check(passwordProblem("onlyletters") !== null && passwordProblem("12345678") !== null, "password rule: letter and number");
check(passwordProblem("Ñandú2026") === null, "password rule: letters in any language count");
check(passwordProblem("a1" + "x".repeat(71)) !== null, "password rule: at most 72 bytes");

reset();
r = await handle(post({ action: "set_password", email: "tim@example.test", code: "ABCD2345", password: "weak" }));
check(r.status === 400 && seen.length === 0, "a weak password is refused before the code is used");

reset(); codeAnswer = { ok: false, message: "The email or code is not right, or the code has expired. Ask your administrator for a new code." };
r = await handle(post({ action: "set_password", email: "tim@example.test", code: "WRONG234", password: "Sunflower7" }));
b = await r.json();
check(r.status === 400 && /not right/.test(b.error) && !urls().some((u) => u.startsWith("PUT")), "a wrong code changes no password");

reset(); codeAnswer = { ok: true, auth_user_id: AUTH, username: "agent_tim" };
r = await handle(post({ action: "set_password", email: "tim@example.test", code: "ABCD2345", password: "Sunflower7" }));
b = await r.json();
const put = seen.find((s) => s.method === "PUT");
check(r.status === 200 && b.ok === true, "right code: password set");
check(put && put.url === `/auth/v1/admin/users/${AUTH}` && JSON.parse(put.body).password === "Sunflower7",
  "the password goes to Supabase Auth for the linked user only");
const use = seen.find((s) => s.url.endsWith("trimax_service_use_setup_code"));
check(JSON.parse(use.body).p_code === "ABCD2345" && !("password" in JSON.parse(use.body)), "the database sees the code, never the password");

reset(); codeAnswer = { ok: true, auth_user_id: AUTH }; putStatus = 422;
r = await handle(post({ action: "set_password", email: "tim@example.test", code: "ABCD2345", password: "Sunflower7" }));
b = await r.json();
check(r.status === 400 && /new code/.test(b.error), "Supabase refuses the password: clear message");

// ---- legacy key
env.SUPABASE_SECRET_KEYS = "";
env.SUPABASE_SERVICE_ROLE_KEY = "eyJlegacy";
reset(); codeAnswer = { ok: false, message: "x" };
await handle(post({ action: "set_password", email: "tim@example.test", code: "ABCD2345", password: "Sunflower7" }));
check(seen[0].apikey === "eyJlegacy" && seen[0].auth === "Bearer eyJlegacy", "legacy service_role key is also sent as Bearer");

server.close();
console.log(fails === 0 ? "ALL TRIMAX-ACCOUNTS CHECKS PASSED" : `${fails} FAILED`);
process.exit(fails === 0 ? 0 : 1);
