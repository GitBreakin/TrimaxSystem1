import http from "node:http";
const env = {};
globalThis.Deno = { env: { get: (k) => env[k] } };
const seen = [];
let mode = "ok";
const JPEG = Buffer.from([0xff, 0xd8, 1, 2, 3, 4, 0xff, 0xd9]);
const server = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    seen.push({ method: req.method, url: req.url, apikey: req.headers["apikey"], auth: req.headers["authorization"], body });
    if (req.url === "/rest/v1/rpc/trimax_service_redeem_media_ticket") {
      if (mode === "expired") { res.writeHead(400, { "Content-Type": "application/json" }); return res.end('{"code":"TM403","message":"expired"}'); }
      if (mode === "weird") { res.writeHead(200, { "Content-Type": "application/json" }); return res.end('{"bucket":"other","path":"x/../y.jpg"}'); }
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end('{"bucket":"duty-photos","path":"00000000-0000-0000-0000-00000000d0d0/start_1_ab.jpg"}');
    }
    if (req.url.startsWith("/storage/v1/object/")) {
      if (mode === "gone") { res.writeHead(400, { "Content-Type": "application/json" }); return res.end('{"statusCode":"404","error":"not_found"}'); }
      res.writeHead(200, { "Content-Type": "image/jpeg" });
      return res.end(JPEG);
    }
    res.writeHead(404); res.end();
  });
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
env.SUPABASE_URL = `http://127.0.0.1:${server.address().port}`;
env.SUPABASE_SECRET_KEYS = JSON.stringify({ default: "sb_secret_test" });
const { handle } = await import("./handler.ts");
let fails = 0;
const check = (ok, label) => { console.log((ok ? "PASS " : "FAIL ") + label); if (!ok) fails++; };
const post = (body) => new Request("http://edge/trimax-admin-media", { method: "POST", body, headers: { "Content-Type": "application/json" } });
const T = "3f2b8c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f";

check((await handle(new Request("http://edge/x"))).status === 405, "GET refused");
check((await handle(post("not json"))).status === 400, "bad body refused");
check((await handle(post('{"ticket":"../../etc"}'))).status === 400, "malformed ticket refused");

seen.length = 0;
let r = await handle(post(JSON.stringify({ ticket: T })));
const bytes = Buffer.from(await r.arrayBuffer());
check(r.status === 200 && r.headers.get("content-type") === "image/jpeg" && bytes.equals(JPEG), "valid ticket returns the photo bytes");
check(r.headers.get("cache-control") === "no-store", "photo is not cached");
check(seen[0].url === "/rest/v1/rpc/trimax_service_redeem_media_ticket" && JSON.parse(seen[0].body).p_ticket === T, "ticket is redeemed first");
check(seen[1].url === "/storage/v1/object/duty-photos/00000000-0000-0000-0000-00000000d0d0/start_1_ab.jpg", "then exactly that photo is downloaded");
check(seen.every((s) => s.apikey === "sb_secret_test" && s.auth === undefined), "new secret key sent as apikey only (never as Bearer)");

env.SUPABASE_SECRET_KEYS = undefined; env.SUPABASE_SERVICE_ROLE_KEY = "eyJlegacy.jwt.key";
seen.length = 0;
r = await handle(post(JSON.stringify({ ticket: T }))); await r.arrayBuffer();
check(r.status === 200 && seen.every((s) => s.apikey === "eyJlegacy.jwt.key" && s.auth === "Bearer eyJlegacy.jwt.key"), "legacy service_role key also works (apikey + Bearer)");

mode = "expired"; seen.length = 0;
r = await handle(post(JSON.stringify({ ticket: T })));
check(r.status === 403 && (await r.json()).error.includes("expired") && seen.length === 1, "used or expired ticket: 403, storage never touched");
mode = "weird"; seen.length = 0;
r = await handle(post(JSON.stringify({ ticket: T })));
check(r.status === 502 && seen.length === 1, "unexpected bucket/path from the database: refused, storage never touched");
mode = "gone";
r = await handle(post(JSON.stringify({ ticket: T })));
check(r.status === 404, "photo deleted from storage: 404");
mode = "ok"; env.SUPABASE_SERVICE_ROLE_KEY = undefined;
r = await handle(post(JSON.stringify({ ticket: T })));
check(r.status === 500, "no server key configured: 500, nothing called");
server.close();
console.log(fails === 0 ? "ALL EDGE FUNCTION CHECKS PASSED" : fails + " FAILED");
process.exit(fails ? 1 : 0);
