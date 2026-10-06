// The full server as an OMB Cloud home machine, over its real HTTP boundary,
// with the settings an Admin from before Cloud Pro dropped included AI still
// sent (OMB_HOSTED_*). Cloud Pro includes no AI: the machine boots, says once
// that it ignores them, serves no gateway models, never hands them (or its
// signing secret) to an engine, and tells the app it pairs that its first run
// is the engine sign-in. It also carries Pro's included Boat computers, voice
// and decision model: offered with no key, their relay tokens never shown,
// saved or passed on. Its bots get the built-in browser and cloud computers,
// never "this computer" or a Local VM. Disposable home; no network; a synthetic
// Claude CLI.
import { randomBytes } from "node:crypto";
import { spawn, type ChildProcess } from "node:child_process";
import { createServer, type Server } from "node:http";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { afterAll, beforeAll, expect, it } from "vitest";
import { CLOUD_HOME_UNOFFERED_PLACE, CLOUD_IGNORED_KEYS, cloudPairingSignature } from "./cloud-home.ts";
import { cloudHomePrompt } from "./system-prompt.ts";
import { removeTempDir, waitForExit } from "./testing/cleanup.ts";
import { freePortBlock } from "./testing/ports.ts";

const SERVER_DIR = dirname(fileURLToPath(import.meta.url));
const HOST = "omb-t-0123456789ab.fly.dev";
const secret = randomBytes(32).toString("base64url");
const token = `omb_cloudai_${randomBytes(32).toString("base64url")}`;
const gateway = {
  OMB_HOSTED_MODEL_URL: "https://cloud.example.test/api/cloud/gateway/g0123456789abcdef0123456789abcd",
  OMB_HOSTED_MODEL_TOKEN: token,
  OMB_HOSTED_MODELS: JSON.stringify({ anthropic: [], openai: ["gpt-fixture"], openrouter: ["anthropic/claude-fixture"] }),
};
// The Admin's relay for the plan's cloud computers, played by a local stub
// (the only address this machine can reach): it creates one ready computer.
const relay = { base: "", requests: [] as Array<{ method: string; path: string; auth: string }>, boxes: [] as Array<{ id: string; name: string; state: string }> };
let relayServer: Server;
// Cloud Pro's included Boat computers, voice and decisions (included-services.ts).
const included = {
  OMB_CLOUD_BOAT_URL: "",
  OMB_CLOUD_BOAT_TOKEN: `box_omb_${randomBytes(24).toString("base64url")}`,
  OMB_CLOUD_VOICE_URL: "https://cloud.example.test/api/cloud/services/voice/v1",
  OMB_CLOUD_VOICE_TOKEN: `omb_voice_${randomBytes(24).toString("base64url")}`,
  OMB_TTS_DEFAULT_VOICE: "preset0voice0id",
  OMB_CLOUD_DECIDER_URL: "https://cloud.example.test/api/cloud/services/decider",
  OMB_CLOUD_DECIDER_TOKEN: `omb_decide_${randomBytes(32).toString("base64url")}`,
};
const includedTokens = [included.OMB_CLOUD_BOAT_TOKEN, included.OMB_CLOUD_VOICE_TOKEN, included.OMB_CLOUD_DECIDER_TOKEN];
let home: string;
let base: string;
let child: ChildProcess;
let log = "";

let ownerToken = "";

/** A request through the edge. Without `remote`, it comes from one of the
 * owner's own devices (paired with the Admin's signed request): on a Cloud
 * home a bare local request is only a service, never the owner. */
async function api(method: string, path: string, options: { body?: unknown; remote?: boolean; headers?: Record<string, string> } = {}) {
  const asOwner = !options.remote && ownerToken !== "";
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      // What the Caddy edge adds to every request it forwards: never the owner.
      ...(options.remote || asOwner ? { host: HOST, "x-forwarded-for": "203.0.113.9", "x-forwarded-proto": "https" } : {}),
      ...(asOwner ? { authorization: `Bearer ${ownerToken}` } : {}),
      ...(options.body === undefined ? {} : { "content-type": "application/json" }),
      ...options.headers,
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  return { status: response.status, body: await response.json().catch(() => null) as any };
}

beforeAll(async () => {
  relayServer = createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://relay.test");
    let raw = "";
    req.on("data", (chunk) => { raw += chunk; });
    req.on("end", () => {
      relay.requests.push({ method: req.method ?? "GET", path: url.pathname, auth: String(req.headers.authorization ?? "") });
      const send = (status: number, payload: unknown) => { res.writeHead(status, { "content-type": "application/json" }); res.end(JSON.stringify(payload)); };
      if (req.headers.authorization !== `Bearer ${included.OMB_CLOUD_BOAT_TOKEN}`) return send(401, { ok: false, code: "unauthorized" });
      const path = url.pathname.replace(/^\/relay\/api\/box\/v1/, "");
      if (path === "/boxes" && req.method === "GET") return send(200, { ok: true, boxes: relay.boxes, pageInfo: { nextCursor: null } });
      if (path === "/boxes" && req.method === "POST") {
        const box = { id: "bx_23456789", name: "", state: "running" };
        relay.boxes.push(box);
        return send(201, { ok: true, box });
      }
      const one = /^\/boxes\/([^/]+)(\/desktop)?$/.exec(path);
      const box = one ? relay.boxes.find((candidate) => candidate.id === one[1]) : undefined;
      if (box && one![2] && req.method === "POST") return send(200, { ok: true, desktopUrl: "https://desktop.relay.test/bx_23456789" });
      if (box && req.method === "PATCH") { Object.assign(box, JSON.parse(raw || "{}")); return send(200, { ok: true, box }); }
      if (box && req.method === "GET") return send(200, { ok: true, box });
      send(404, { ok: false, code: "not_found", message: "Not found" });
    });
  });
  await new Promise<void>((resolve) => relayServer.listen(0, "127.0.0.1", resolve));
  relay.base = `http://127.0.0.1:${(relayServer.address() as { port: number }).port}`;
  included.OMB_CLOUD_BOAT_URL = `${relay.base}/relay/api/box/v1`;
  home = mkdtempSync(join(tmpdir(), "omb-cloud-home-server-"));
  const dataDir = join(home, ".openmausbot");
  mkdirSync(dataDir, { recursive: true });
  // A signed-in Claude Code whose turns record the environment they were given.
  // While the hang marker exists, a new turn records itself elsewhere and
  // stays running, so its tool token stays live. While the screen marker
  // exists, a turn given the cloud computer's tools takes one screenshot first,
  // the way a model's first computer call starts a lazily started computer.
  const cli = join(home, "fixture-claude.mjs");
  writeFileSync(cli, `#!/usr/bin/env node
import { existsSync } from "node:fs";
if (process.argv[2] === "auth") {
  console.log(JSON.stringify({ loggedIn: true, email: "person@example.test" }));
  process.exit(0);
}
const hang = existsSync(${JSON.stringify(join(home, "hang"))});
if (hang) process.env.FAKE_CLAUDE_MODE = "hang";
if (existsSync(${JSON.stringify(join(home, "screen"))})) process.env.FAKE_CLAUDE_USES_CLOUD_COMPUTER = "1";
if (process.argv[2] !== "--version") process.env.FAKE_CLAUDE_DUMP = ${JSON.stringify(join(home, "spawn"))} + (hang ? "-hang.json" : ".json");
await import(${JSON.stringify(pathToFileURL(join(SERVER_DIR, "testing", "fake-claude-cli.ts")).href)});
`, { mode: 0o755 });
  writeFileSync(join(dataDir, "config.json"), JSON.stringify({
    instances: {
      // Pin the fleet's other defaults so this never probes an installed CLI.
      ...Object.fromEntries(["codex", "cursor", "openaiCompat", "qwen", "hermes", "pi"].map((id) => [id, { driver: "not-a-real-driver" }])),
      claude: { driver: "claudeAgent", displayName: "Claude", config: { cli } },
    },
  }));
  // The web UI's pages (a stand-in for the built app).
  mkdirSync(join(home, "web"));
  writeFileSync(join(home, "web", "index.html"), "<!doctype html><title>OpenMausBot</title>");
  port = await freePortBlock([0, 1]);
  base = `http://127.0.0.1:${port}`;
  await start();
}, 30_000);

let port = 0;
/** Start the Cloud home on this disposable home, as its machine does after
 * every update or restart, and pair one of the owner's devices with it. */
async function start() {
  const dataDir = join(home, ".openmausbot");
  // Offline but for the relay stub.
  const offlinePrelude = `data:text/javascript,${encodeURIComponent(`const reach = globalThis.fetch, relay = ${JSON.stringify(relay.base)};
globalThis.fetch = async (input, init) => String(input?.url ?? input).startsWith(relay) ? reach(input, init) : new Response("offline fixture", { status: 503 });`)}`;
  child = spawn(process.execPath, ["--import", offlinePrelude, join(SERVER_DIR, "index.ts")], {
    cwd: join(SERVER_DIR, ".."),
    env: {
      PATH: process.env.PATH,
      ...(process.env.PATHEXT ? { PATHEXT: process.env.PATHEXT } : {}),
      ...(process.env.SystemRoot ? { SystemRoot: process.env.SystemRoot } : {}),
      HOME: home, USERPROFILE: home, OMB_DATA_DIR: dataDir, OMB_PORT: String(port), OMB_WEBHOOK_PORT: String(port + 1), OMB_STATIC_DIR: join(home, "web"),
      OMB_CLOUD_ROLE: "home", OMB_CLOUD_MACHINE_ID: "3f9c2a4e-8b1d-4c6e-9a7f-2d5e8c1b0a93", OMB_CLOUD_ADMIN_URL: "https://cloud.example.test",
      OMB_CLOUD_BOOTSTRAP_SECRET: secret, OMB_PUBLIC_URL: `https://${HOST}`,
      ...gateway,
      ...included,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout?.on("data", (chunk) => { log += chunk; });
  child.stderr?.on("data", (chunk) => { log += chunk; });
  // A bare local request until a device is paired again.
  ownerToken = "";
  const deadline = Date.now() + 20_000;
  for (;;) {
    if (child.exitCode !== null) throw new Error(`the Cloud home exited:\n${log}`);
    try { if ((await api("GET", "/api/health")).body?.pid === child.pid) break; } catch { /* starting */ }
    if (Date.now() > deadline) throw new Error(`the Cloud home did not start:\n${log}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  ownerToken = await ownerPairing();
}

/** One of the owner's devices, paired the way the Admin pairs the app. */
async function ownerPairing(): Promise<string> {
  const body = JSON.stringify({ label: "OpenMausBot app (Cloud)", ttlSeconds: 300 });
  const timestamp = String(Math.floor(Date.now() / 1000)), nonce = randomBytes(16).toString("base64url");
  const granted = await api("POST", "/api/cloud/pairing", { remote: true, headers: {
    "content-type": "application/json", "x-omb-cloud-timestamp": timestamp, "x-omb-cloud-nonce": nonce,
    "x-omb-cloud-signature": `v1=${cloudPairingSignature(secret, timestamp, nonce, body)}`,
  }, body: JSON.parse(body) });
  return (await api("POST", "/api/auth/pair", { remote: true, body: { code: granted.body.code } })).body.token as string;
}

afterAll(async () => {
  if (child) await waitForExit(child, { signal: "SIGTERM" });
  if (relayServer) await new Promise<void>((resolve) => relayServer.close(() => resolve()));
  if (home) await removeTempDir(home);
});

it("boots with a gateway's settings, says once that it ignores them, and never logs them", () => {
  expect(log.match(/cloud home: ignoring OMB_HOSTED_MODEL_URL, OMB_HOSTED_MODEL_TOKEN, OMB_HOSTED_MODELS: Cloud Pro includes no AI/g)).toHaveLength(1);
  expect(log).not.toContain(token);
  expect(log).not.toContain(secret);
  for (const includedToken of includedTokens) expect(log).not.toContain(includedToken);
});

it("offers the included computers, voice and decisions with no key, and never shows or saves their tokens", async () => {
  const status = await api("GET", "/api/config");
  expect(status.status).toBe(200);
  expect(status.body.box).toEqual({ configured: true, included: true });
  expect(status.body.tts).toMatchObject({ configured: true, ready: true, provider: "elevenlabs", voice: "preset0voice0id", included: true });
  expect(status.body.decider).toEqual({ provider: "jev", configured: true, included: true, enabled: true, jobs: { roomRouting: true } });
  const saved = readFileSync(join(home, ".openmausbot", "config.json"), "utf8");
  for (const includedToken of includedTokens) {
    expect(JSON.stringify(status.body)).not.toContain(includedToken);
    expect(saved).not.toContain(includedToken);
  }
});

it("pairs the app on a signed request and tells it its first run is the engine sign-in; no gateway models are served", async () => {
  const body = JSON.stringify({ label: "OpenMausBot app (Cloud)", ttlSeconds: 300 });
  const timestamp = String(Math.floor(Date.now() / 1000)), nonce = randomBytes(16).toString("base64url");
  const granted = await api("POST", "/api/cloud/pairing", { remote: true, headers: {
    "content-type": "application/json", "x-omb-cloud-timestamp": timestamp, "x-omb-cloud-nonce": nonce,
    "x-omb-cloud-signature": `v1=${cloudPairingSignature(secret, timestamp, nonce, body)}`,
  }, body: JSON.parse(body) });
  expect(granted.status, JSON.stringify(granted.body)).toBe(200);
  const paired = await api("POST", "/api/auth/pair", { remote: true, body: { code: granted.body.code } });
  expect(paired.status, JSON.stringify(paired.body)).toBe(200);
  const auth = { authorization: `Bearer ${paired.body.token}` };
  const session = await api("GET", "/api/auth/session", { remote: true, headers: auth });
  expect(session.body).toMatchObject({ kind: "session", scopes: ["admin", "client"], cloudHome: true });
  expect(session.body).not.toHaveProperty("hosted");
  // No bot has finished a turn here yet: the setup checklist's step is open.
  expect((await api("GET", "/api/config", { remote: true, headers: auth })).body.onboarding).not.toHaveProperty("firstTurnAt");
  const { instances } = (await api("GET", "/api/instances", { remote: true, headers: auth })).body;
  expect(instances.map((instance: any) => instance.instanceId)).toContain("claude");
  expect(instances.filter((instance: any) => instance.instanceId.startsWith("included.") || "included" in instance || instance.readOnly)).toEqual([]);
  expect(JSON.stringify(instances)).not.toContain("cloud.example.test");
});

/** The Admin's signed request for a browser sign-in on this machine, for `owner`'s Cloud. */
async function mintBrowserSignIn(owner = "ada@example.test"): Promise<string> {
  const body = JSON.stringify({ label: "Web browser (Cloud page)", ttlSeconds: 120, purpose: "browser", owner });
  const timestamp = String(Math.floor(Date.now() / 1000)), nonce = randomBytes(16).toString("base64url");
  const granted = await api("POST", "/api/cloud/pairing", { remote: true, headers: {
    "content-type": "application/json", "x-omb-cloud-timestamp": timestamp, "x-omb-cloud-nonce": nonce,
    "x-omb-cloud-signature": `v1=${cloudPairingSignature(secret, timestamp, nonce, body)}`,
  }, body: JSON.parse(body) });
  expect(granted.status, JSON.stringify(granted.body)).toBe(200);
  expect(granted.body).toMatchObject({ purpose: "browser", credential: expect.stringMatching(/^omb_pair_/) });
  expect(granted.body).not.toHaveProperty("code");
  return granted.body.credential as string;
}
/** The web page's own requests (src/lib/session.ts): what it shows first, then Continue. */
const browserRequest = (body: Record<string, unknown>, cookie?: string) => fetch(`${base}/api/auth/pair`, { method: "POST", headers: {
  host: HOST, "x-forwarded-for": "203.0.113.9", "x-forwarded-proto": "https", "content-type": "application/json", ...(cookie ? { cookie } : {}) }, body: JSON.stringify(body) });

it("signs a browser in from the Cloud page's \"Use in your browser\" into an owner's session, once, and never logs it", async () => {
  const credential = await mintBrowserSignIn();
  // An app's exchange, or one asking for a bearer token instead of this browser's cookie, gets nothing and leaves it open.
  expect((await api("POST", "/api/auth/pair", { remote: true, body: { code: credential } })).status).toBe(401);
  expect((await api("POST", "/api/pair", { remote: true, body: { credential } })).status).toBe(401);
  expect((await api("POST", "/api/auth/pair", { remote: true, body: { code: credential, browser: true } })).status).toBe(400);
  // Before anything is redeemed the page shows whose Cloud this is; looking redeems nothing.
  for (let i = 0; i < 2; i++) {
    const preview = await browserRequest({ code: credential, browser: true, preview: true });
    expect(preview.status).toBe(200);
    expect(await preview.json()).toEqual({ owner: "ada@example.test", expiresAt: expect.any(Number) });
    expect(preview.headers.get("set-cookie")).toBeNull();
  }
  const signIn = () => browserRequest({ code: credential, label: "Safari on iPad", cookie: true, browser: true, attemptId: randomBytes(12).toString("base64url") });
  const signedIn = await signIn();
  expect(signedIn.status).toBe(200);
  expect(await signedIn.json()).not.toHaveProperty("token");
  const cookie = signedIn.headers.get("set-cookie") ?? "";
  expect(cookie).toMatch(/HttpOnly/);
  expect(cookie).toMatch(/Secure/);
  expect(cookie).toMatch(/SameSite=Lax/);
  const session = await api("GET", "/api/auth/session", { remote: true, headers: { cookie: cookie.split(";")[0] } });
  // The owner's admin scope, exactly what the app gets from its own Cloud pairing, and whose Cloud it is.
  expect(session.body).toMatchObject({ kind: "session", label: "Safari on iPad", scopes: ["admin", "client"], cloudHome: true, owner: "ada@example.test" });
  // Its cookie's value is not a bearer token.
  const token = cookie.split(";")[0].split("=").slice(1).join("=");
  expect((await api("GET", "/api/auth/session", { remote: true, headers: { authorization: `Bearer ${token}` } })).status).toBe(401);
  // Its changes need the browser's word that they come from this Cloud's own page: the cookie alone is refused.
  const change = (headers: Record<string, string>) => api("POST", "/api/auth/stream-ticket", { remote: true, headers: { cookie: cookie.split(";")[0], ...headers } });
  expect((await change({})).status).toBe(403);
  expect((await change({ "sec-fetch-site": "cross-site" })).status).toBe(403);
  // (fetch sends its own Host here, so this Cloud's origin is the proxied scheme plus the fixture's address)
  expect((await change({ origin: base.replace("http:", "https:") })).status).toBe(200);
  expect((await change({ origin: `https://evil.example`, "sec-fetch-site": "same-origin" })).status).toBe(403);
  expect((await change({ "sec-fetch-site": "same-origin" })).status).toBe(200);
  // A replay, or looking at a spent one, gets nothing.
  expect((await signIn()).status).toBe(401);
  expect((await browserRequest({ code: credential, browser: true, preview: true })).status).toBe(401);
  expect(log).not.toContain(credential);
});

it("replaces a browser's own session when it signs in again, and a lost answer leaves a named session that can be revoked", async () => {
  const cookieOf = (response: Response) => (response.headers.get("set-cookie") ?? "").split(";")[0];
  const first = await browserRequest({ code: await mintBrowserSignIn(), label: "Chrome on Mac", cookie: true, browser: true, attemptId: randomBytes(12).toString("base64url") });
  expect(first.status).toBe(200);
  const firstCookie = cookieOf(first), firstId = ((await first.json()) as any).session.id as string;
  // Signing in again from the same browser, already connected: the new session replaces the old one.
  const second = await browserRequest({ code: await mintBrowserSignIn(), label: "Chrome on Mac", cookie: true, browser: true, attemptId: randomBytes(12).toString("base64url") }, firstCookie);
  expect(second.status).toBe(200);
  const secondCookie = cookieOf(second), secondId = ((await second.json()) as any).session.id as string;
  expect((await api("GET", "/api/auth/session", { remote: true, headers: { cookie: firstCookie } })).status).toBe(401);
  const listed = await api("GET", "/api/auth/sessions", { remote: true, headers: { cookie: secondCookie } });
  expect(listed.body.sessions.map((s: any) => s.id)).toContain(secondId);
  expect(listed.body.sessions.map((s: any) => s.id)).not.toContain(firstId);
  // An answer that never arrives leaves a session named for its browser in Paired devices, which the owner can revoke.
  const lost = await browserRequest({ code: await mintBrowserSignIn(), label: "Firefox on Chromebook", cookie: true, browser: true, attemptId: randomBytes(12).toString("base64url") });
  const lostId = ((await lost.json()) as any).session.id as string;
  const orphan = (await api("GET", "/api/auth/sessions", { remote: true, headers: { cookie: secondCookie } })).body.sessions.find((s: any) => s.id === lostId);
  expect(orphan).toMatchObject({ label: "Firefox on Chromebook", scopes: ["admin", "client"], owner: "ada@example.test" });
  // (as the Cloud's own page sends it: a browser marks the change same-origin)
  const revoke = await fetch(`${base}/api/auth/sessions/${lostId}`, { method: "DELETE", headers: { host: HOST, "x-forwarded-for": "203.0.113.9", "x-forwarded-proto": "https",
    "sec-fetch-site": "same-origin", cookie: secondCookie } });
  expect(revoke.status).toBe(200);
  expect((await api("GET", "/api/auth/session", { remote: true, headers: { cookie: cookieOf(lost) } })).status).toBe(401);
});

it("serves its pages to no frame and with no Referer", async () => {
  for (const path of ["/pair", "/", "/settings"]) {
    const page = await fetch(`${base}${path}`, { headers: { host: HOST, "x-forwarded-for": "203.0.113.9", "x-forwarded-proto": "https" } });
    expect(page.status, path).toBe(200);
    expect(page.headers.get("content-type"), path).toBe("text/html");
    expect(page.headers.get("content-security-policy"), path).toBe("frame-ancestors 'none'");
    expect(page.headers.get("x-frame-options"), path).toBe("DENY");
    expect(page.headers.get("referrer-policy"), path).toBe("no-referrer");
  }
});

it("never hands the included tokens or the signing secret to a CLI it probes", async () => {
  // POST /api/cli-test runs `<cli> --version` with a copy of the server's own
  // environment, less every credential on the shared lists (config.ts). That
  // the server drops the included tokens from its own environment at startup
  // is holdIncludedServices (included-services.test.ts).
  const dump = join(home, "cli-env.json");
  const cli = join(home, "dump-env.mjs");
  writeFileSync(cli, `#!/usr/bin/env node
import { writeFileSync } from "node:fs";
writeFileSync(${JSON.stringify(dump)}, JSON.stringify(process.env));
console.log("dump-env 1.0.0");
`, { mode: 0o755 });
  const probe = await api("POST", "/api/cli-test", { body: { cli } });
  expect(probe.body, JSON.stringify(probe.body)).toMatchObject({ ok: true, version: "dump-env 1.0.0" });
  const env = JSON.parse(readFileSync(dump, "utf8"));
  // Proves the dump is the server's environment, not an empty one.
  expect(env.OMB_TTS_DEFAULT_VOICE).toBe(included.OMB_TTS_DEFAULT_VOICE);
  for (const key of ["OMB_CLOUD_BOAT_TOKEN", "OMB_CLOUD_VOICE_TOKEN", "OMB_CLOUD_DECIDER_TOKEN", "OMB_CLOUD_BOOTSTRAP_SECRET"]) expect(env).not.toHaveProperty(key);
  for (const value of [...includedTokens, secret]) expect(JSON.stringify(env)).not.toContain(value);
});

it("does not count a turn that was stopped before it finished", async () => {
  writeFileSync(join(home, "hang"), "");
  const created = await api("POST", "/api/bots", { body: {
    name: "Stopped fixture", modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
  } });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const botId = created.body.bot.id;
  const dump = join(home, "spawn-hang.json");
  try {
    expect((await api("POST", `/api/bots/${botId}/messages`, { body: { text: "wait for me" } })).status).toBe(202);
    await expect.poll(() => existsSync(dump), { timeout: 15_000 }).toBe(true);
  } finally {
    rmSync(join(home, "hang"), { force: true });
    await api("POST", `/api/bots/${botId}/interrupt`, { body: {} });
  }
  const busy = async () => {
    const { bots } = (await api("GET", "/api/bots?messages=10")).body as { bots: Array<{ id: string; busy?: boolean }> };
    return bots.find((bot) => bot.id === botId)?.busy === true;
  };
  await expect.poll(busy, { timeout: 15_000 }).toBe(false);
  expect((await api("GET", "/api/config")).body.onboarding).not.toHaveProperty("firstTurnAt");
  // A later test reads the next hanging turn's own record.
  rmSync(dump, { force: true });
});

it("never hands a gateway's settings or the signing secret to an engine", async () => {
  const created = await api("POST", "/api/bots", { body: {
    name: "Cloud fixture", modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
  } });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  expect((await api("POST", `/api/bots/${created.body.bot.id}/messages`, { body: { text: "hello" } })).status).toBe(202);
  const dump = join(home, "spawn.json");
  await expect.poll(() => existsSync(dump), { timeout: 15_000 }).toBe(true);
  const { env } = JSON.parse(readFileSync(dump, "utf8"));
  expect(env.HOME).toBe(home);
  for (const key of [...CLOUD_IGNORED_KEYS, "OMB_CLOUD_BOOTSTRAP_SECRET", "OMB_CLOUD_BOAT_TOKEN", "OMB_CLOUD_VOICE_TOKEN", "OMB_CLOUD_DECIDER_TOKEN"]) expect(env).not.toHaveProperty(key);
  expect(JSON.stringify(env)).not.toContain(token);
  expect(JSON.stringify(env)).not.toContain(secret);
  for (const includedToken of includedTokens) expect(JSON.stringify(env)).not.toContain(includedToken);
});

it("records when a bot's turn first finished here, once, in the Cloud's own settings", async () => {
  // The turn above ("hello") finished on this machine.
  const first = async () => (await api("GET", "/api/config")).body.onboarding?.firstTurnAt as string | undefined;
  await expect.poll(first, { timeout: 15_000 }).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  const recorded = await first();
  expect(JSON.parse(readFileSync(join(home, ".openmausbot", "config.json"), "utf8")).onboarding.firstTurnAt).toBe(recorded);
  // A plain chat held no cloud computer: Give a bot a cloud computer stays to do.
  expect((await api("GET", "/api/config")).body.onboarding).not.toHaveProperty("firstCloudComputerAt");
  // A later turn leaves it as it was.
  const created = await api("POST", "/api/bots", { body: {
    name: "Second fixture", modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
  } });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const botId = created.body.bot.id;
  expect((await api("POST", `/api/bots/${botId}/messages`, { body: { text: "hello again" } })).status).toBe(202);
  const replied = async () => {
    const { bots } = (await api("GET", "/api/bots?messages=10")).body as { bots: Array<{ id: string; busy?: boolean; messages: Array<{ role: string; kind: string }> }> };
    const bot = bots.find((entry) => entry.id === botId);
    return Boolean(bot && !bot.busy && bot.messages.some((message) => message.role === "bot" && message.kind === "text"));
  };
  await expect.poll(replied, { timeout: 15_000 }).toBe(true);
  expect(await first()).toBe(recorded);
});

// J4's record half: the first turn that finishes OK holding a cloud computer
// ticks Set up My Cloud's "Give a bot a cloud computer", once.
it("records when a turn first finished with a cloud computer, through the plan's relay", async () => {
  const created = await api("POST", "/api/bots", { body: {
    name: "Screen fixture", modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
  } });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const botId = created.body.bot.id as string;
  expect((await api("PATCH", `/api/bots/${botId}`, { body: { computer: "cloud" } })).status).toBe(200);
  expect((await api("GET", "/api/config")).body.onboarding).not.toHaveProperty("firstCloudComputerAt");
  const recorded = async () => (await api("GET", "/api/config")).body.onboarding?.firstCloudComputerAt as string | undefined;
  // A cloud computer starts only when the bot uses it: this turn's first call
  // is a screenshot.
  const screen = join(home, "screen");
  writeFileSync(screen, "");
  try {
    expect((await api("POST", `/api/bots/${botId}/messages`, { body: { text: "Open a web browser on your cloud computer" } })).status).toBe(202);
    await expect.poll(recorded, { timeout: 30_000 }).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  } finally {
    rmSync(screen, { force: true });
  }
  // The turn held the plan's computer, made through the relay with the plan's token only.
  expect(relay.requests.some((request) => request.method === "POST" && request.path === "/relay/api/box/v1/boxes")).toBe(true);
  expect(relay.requests.every((request) => request.auth === `Bearer ${included.OMB_CLOUD_BOAT_TOKEN}`)).toBe(true);
  const at = await recorded();
  expect(JSON.parse(readFileSync(join(home, ".openmausbot", "config.json"), "utf8")).onboarding.firstCloudComputerAt).toBe(at);
  // The first turn's time stays as it was.
  expect((await api("GET", "/api/config")).body.onboarding.firstTurnAt < at!).toBe(true);
  const busy = async () => (await api("GET", "/api/bots?messages=0")).body.bots.find((bot: { id: string; busy?: boolean }) => bot.id === botId)?.busy === true;
  await expect.poll(busy, { timeout: 15_000 }).toBe(false);
  expect((await api("PATCH", `/api/bots/${botId}`, { body: { computer: null } })).status).toBe(200);
}, 60_000);

it("offers its bots the browser and cloud computers only, and tells them they cannot see the person's computer", async () => {
  // The browser is on with no welcome to turn it on; the app is told this is
  // a Cloud home, so it lists no this computer and no Local VM either.
  const status = await api("GET", "/api/config");
  expect(status.body).toMatchObject({ cloudHome: true, features: { browser: true } });
  // Settings in a browser here links to the Plan page on this Cloud's Admin.
  expect(status.body.cloudPlanPage).toBe("https://cloud.example.test/cloud");
  writeFileSync(join(home, "hang"), "");
  const created = await api("POST", "/api/bots", { body: {
    name: "Desk fixture", modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
  } });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const botId = created.body.bot.id;
  try {
    expect((await api("POST", `/api/bots/${botId}/messages`, { body: { text: "list the files on my desktop" } })).status).toBe(202);
    const dump = join(home, "spawn-hang.json");
    // The fake CLI writes its record in place: read it once it is whole.
    const whole = () => { try { return JSON.parse(readFileSync(dump, "utf8")) as { systemPrompt: string; mcpConfig: any }; } catch { return null; } };
    await expect.poll(whole, { timeout: 15_000 }).not.toBeNull();
    const { systemPrompt, mcpConfig } = whole()!;
    // Its engine mounts the team tools, and a Cloud home always offers lending,
    // so the bot is told how to reach a lent Mac, and what to say without one.
    expect(systemPrompt).toContain(cloudHomePrompt(true));
    expect(systemPrompt).toContain("check list_shared_computers");
    expect(systemPrompt).not.toMatch(/Local VM is an isolated desktop|user's host|host desktop|select an available Local VM/);
    const agents = mcpConfig.mcpServers.agents;
    expect(agents.env.OMB_CLOUD_HOME).toBe("1");
    const preview = (await api("GET", `/api/bots/${botId}/system-prompt`)).body.sections as Array<{ id: string; text: string }>;
    expect(preview.find((section) => section.id === "cloud-home")?.text).toBe(cloudHomePrompt(true));
    const select = (surface?: string) => fetch(`${base}/api/internal/computer/select`, {
      method: surface === undefined ? "GET" : "POST",
      headers: { authorization: `Bearer ${agents.env.OMB_COMMS_TOKEN}`, ...(surface === undefined ? {} : { "content-type": "application/json" }) },
      ...(surface === undefined ? {} : { body: JSON.stringify({ surface }) }),
    });
    const listed = await (await select()).json() as { canSelect: boolean; options: Array<{ surface: string }> };
    expect(listed.canSelect).toBe(true);
    expect(listed.options.map((option) => option.surface)).toEqual(["cloud", "browser"]);
    expect(JSON.stringify(listed)).not.toMatch(/this computer|Local VM|container runtime/i);
    for (const surface of ["local", "vm"] as const) {
      const refused = await select(surface);
      expect(refused.status).toBe(409);
      expect(((await refused.json()) as { error: string }).error).toBe(CLOUD_HOME_UNOFFERED_PLACE);
    }
  } finally {
    rmSync(join(home, "hang"), { force: true });
    await api("POST", `/api/bots/${botId}/interrupt`, { body: {} });
  }
});

// J8. Copy this computer here installs a desktop's bots when the Cloud next
// starts (before anything loads), and a Cloud may hold bots from before this
// rule. Either way a bot set to This computer or a Local VM, or a chat pinned
// to one, works on Auto from that start: answered, with no refusal to read.
it("J8: a bot copied in set to This computer or a Local VM works on Auto after the Cloud starts, with no refusal", async () => {
  const copied: Record<string, string> = {};
  for (const computer of ["local", "vm"] as const) {
    const created = await api("POST", "/api/bots", { body: {
      name: `Copied ${computer} fixture`, modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
    } });
    expect(created.status, JSON.stringify(created.body)).toBe(201);
    copied[computer] = created.body.bot.id;
  }
  const pinned = (await api("GET", "/api/bots?messages=0")).body.bots.find((bot: { id: string }) => bot.id === copied.local);

  // The copy lands while the Cloud is stopped, as Copy this computer here
  // installs it: bots and a chat set to places this machine does not offer.
  await waitForExit(child, { signal: "SIGTERM" });
  const botsFile = join(home, ".openmausbot", "bots.json");
  const records = JSON.parse(readFileSync(botsFile, "utf8")) as Array<{ id: string; computer?: string; tasks?: Array<{ threadId: string; surface?: string; surfaceSource?: string }> }>;
  for (const record of records) {
    if (record.id === copied.local) {
      record.computer = "local";
      const task = record.tasks?.find((candidate) => candidate.threadId === pinned.threadId);
      Object.assign(task!, { surface: "vm", surfaceSource: "user" });
    }
    if (record.id === copied.vm) record.computer = "vm";
  }
  writeFileSync(botsFile, JSON.stringify(records));
  await start();

  const bots = (await api("GET", "/api/bots?messages=0")).body.bots as Array<{ id: string; threadId: string; computer?: string; tasks?: Array<{ threadId: string; surface?: string }> }>;
  for (const botId of Object.values(copied)) expect(bots.find((bot) => bot.id === botId)).not.toHaveProperty("computer");
  const local = bots.find((bot) => bot.id === copied.local)!;
  expect(local.tasks?.find((task) => task.threadId === pinned.threadId)).not.toHaveProperty("surface");

  expect((await api("POST", `/api/bots/${copied.local}/messages`, { body: { text: "list the files on my desktop" } })).status).toBe(202);
  const settled = async () => {
    const { bots } = (await api("GET", "/api/bots?messages=10")).body as { bots: Array<{ id: string; busy?: boolean; messages: Array<{ role: string; kind: string; text?: string; tool?: { name: string; ok: boolean } }> }> };
    const bot = bots.find((entry) => entry.id === copied.local);
    return bot && !bot.busy && bot.messages.some((message) => message.role === "bot" && message.kind === "text") ? bot.messages : null;
  };
  await expect.poll(settled, { timeout: 15_000 }).not.toBeNull();
  const messages = (await settled())!;
  expect(messages.filter((message) => message.tool?.ok === false)).toEqual([]);
  expect(JSON.stringify(messages)).not.toMatch(/isn't a place|can't use a Local VM|Works on|has no This computer/);
}, 60_000);

// While it runs, nothing can set either place again: a bot's Works on and a
// conversation's place are refused in plain words, so no bot ends up refused
// on every message until the next restart.
it("refuses This computer or a Local VM as a bot's Works on or a conversation's place", async () => {
  const created = await api("POST", "/api/bots", { body: {
    name: "Place fixture", modelSelection: { instanceId: "claude", model: "claude-sonnet-5" }, requireAvailableModel: true,
  } });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const botId = created.body.bot.id as string;
  const threadId = (await api("GET", "/api/bots?messages=0")).body.bots.find((bot: { id: string }) => bot.id === botId).threadId as string;
  for (const place of ["local", "vm"] as const) {
    const works = await api("PATCH", `/api/bots/${botId}`, { body: { computer: place } });
    expect(works.status, JSON.stringify(works.body)).toBe(409);
    expect(works.body.error).toBe(CLOUD_HOME_UNOFFERED_PLACE);
    const chat = await api("PATCH", `/api/bots/${botId}/tasks/${threadId}`, { body: { surface: place } });
    expect(chat.status, JSON.stringify(chat.body)).toBe(409);
    expect(chat.body.error).toBe(CLOUD_HOME_UNOFFERED_PLACE);
  }
  const bot = (await api("GET", "/api/bots?messages=0")).body.bots.find((entry: { id: string }) => entry.id === botId);
  expect(bot).not.toHaveProperty("computer");
  expect(bot.tasks.find((task: { threadId: string }) => task.threadId === threadId)).not.toHaveProperty("surface");
  // The places it offers are still saved.
  expect((await api("PATCH", `/api/bots/${botId}`, { body: { computer: "cloud" } })).status).toBe(200);
  expect((await api("PATCH", `/api/bots/${botId}/tasks/${threadId}`, { body: { surface: "browser" } })).status).toBe(200);
  expect((await api("PATCH", `/api/bots/${botId}`, { body: { computer: null } })).status).toBe(200);
});
