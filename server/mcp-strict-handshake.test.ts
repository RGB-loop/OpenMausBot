// A URL MCP server that deserializes initialize strictly (a Voluum
// server: "Unrecognized field 'schemaValidation'") against the
// handshakes the engines really send. The payloads below were captured from
// the real binaries (Oct 8 2026) talking to testing/fake-http-mcp-server.ts
// with `strictInitialize`; OMB's own client and its stdio proxy send the
// minimal handshake and pass.
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { once } from "node:events";
import { createInterface } from "node:readline";
import { afterEach, describe, expect, it } from "vitest";

import { mcpStdioServer } from "./mcp-gate-config.ts";
import { RemoteMcpClient } from "./mcp-http.ts";
import { startFakeHttpMcp, unrecognizedField, STRICT_INITIALIZE_SCHEMAS, type FakeHttpMcp } from "./testing/fake-http-mcp-server.ts";

/** codex-cli 0.160.1 (also 0.144.4, 0.149.0) mounting a URL server natively */
const CODEX = { protocolVersion: "2025-06-18", capabilities: { elicitation: { form: {}, url: {} } }, clientInfo: { name: "codex-mcp-client", title: "Codex", version: "0.160.1" } };
/** Claude Code 2.1.292, after its server/discover probe */
const CLAUDE = {
  protocolVersion: "2025-11-25",
  capabilities: { roots: { listChanged: true }, elicitation: { form: {}, url: {} } },
  clientInfo: { name: "claude-code", title: "Claude Code", version: "2.1.292", description: "Anthropic's agentic coding tool", websiteUrl: "https://claude.com/claude-code" },
};
/** grok 1.0.25 (`grok mcp doctor`; a session needs a grok.com sign-in) */
const GROK = { protocolVersion: "2025-11-25", capabilities: { extensions: { "io.modelcontextprotocol/ui": { mimeTypes: ["text/html;profile=mcp-app"] } } }, clientInfo: { name: "grok-shell-voluum", version: "1.0.25" } };
/** rmcp's FormElicitationCapability with schema validation turned on: the
 * field a Voluum server named */
const SCHEMA_VALIDATION = { ...CODEX, capabilities: { elicitation: { form: { schemaValidation: true }, url: {} } } };

const TOOLS = [{ name: "report", inputSchema: { type: "object" } }, { name: "campaigns", inputSchema: { type: "object" } }];

async function post(url: string, params: unknown) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params }),
  });
  return { status: response.status, body: await response.json() as { result?: unknown; error?: { message: string } } };
}

describe("strict initialize fixture", () => {
  let fake: FakeHttpMcp | undefined;
  let child: ChildProcessWithoutNullStreams | undefined;
  afterEach(async () => {
    if (child && child.exitCode === null && child.signalCode === null) {
      const closed = once(child, "close"); child.kill(); await closed;
    }
    child = undefined;
    await fake?.close(); fake = undefined;
  });

  it("names the first unknown field of each engine's handshake", () => {
    const base = STRICT_INITIALIZE_SCHEMAS["2025-06-18"];
    const newer = STRICT_INITIALIZE_SCHEMAS["2025-11-25"];
    expect(unrecognizedField(CODEX, base)).toBe("capabilities.elicitation.form");
    expect(unrecognizedField(CLAUDE, base)).toBe("capabilities.elicitation.form");
    expect(unrecognizedField(GROK, base)).toBe("capabilities.extensions");
    expect(unrecognizedField(CODEX, newer)).toBeUndefined();
    expect(unrecognizedField(CLAUDE, newer)).toBeUndefined();
    expect(unrecognizedField(GROK, newer)).toBe("capabilities.extensions");
    expect(unrecognizedField(SCHEMA_VALIDATION, newer)).toBe("capabilities.elicitation.form.schemaValidation");
  });

  it("refuses an unknown field the way a Jackson-based server does", async () => {
    fake = await startFakeHttpMcp({ strictInitialize: "http-400", strictSchema: "2025-11-25", tools: TOOLS });
    const refused = await post(fake.url, SCHEMA_VALIDATION);
    expect(refused.status).toBe(400);
    expect(refused.body.error?.message).toContain("Unrecognized field 'schemaValidation'");
    const accepted = await post(fake.url, CODEX);
    expect(accepted.status).toBe(200);
    expect(accepted.body.result).toBeDefined();
    expect(fake.initializes).toEqual([SCHEMA_VALIDATION, CODEX]);
  });

  it("accepts OMB's own client, which sends the minimal handshake", async () => {
    fake = await startFakeHttpMcp({ strictInitialize: "http-400", tools: TOOLS });
    const client = new RemoteMcpClient({ type: "http", url: fake.url, headers: {} });
    await client.initialize("OpenMausBot", AbortSignal.timeout(5_000));
    const listed = await client.request("tools/list", {}, AbortSignal.timeout(5_000)) as { tools: Array<{ name: string }> };
    expect(listed.tools.map((tool) => tool.name)).toEqual(["report", "campaigns"]);
    await client.close();
    expect(fake.initializes).toEqual([{ protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "OpenMausBot", version: "1" } }]);
  });

  it.each([
    ["Codex", CODEX],
    ["Claude Code", CLAUDE],
    ["Grok", GROK],
    ["schemaValidation", SCHEMA_VALIDATION],
  ])("serves %s's handshake through OMB's stdio proxy, forwarding none of its fields", async (_name, handshake) => {
    fake = await startFakeHttpMcp({ strictInitialize: "http-400", tools: TOOLS });
    const descriptor = mcpStdioServer({ type: "http", url: fake.url, headers: {} })!;
    child = spawn(descriptor.command, ["--experimental-strip-types", "--no-warnings", ...descriptor.args!], { stdio: "pipe", env: { ...process.env, ...descriptor.env } });
    const lines = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
    const ask = async (id: number, method: string, params?: unknown) => {
      child!.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, ...(params ? { params } : {}) })}\n`);
      return JSON.parse((await lines.next()).value as string) as { result?: any; error?: unknown };
    };
    // the engine's own handshake would be refused upstream; the proxy's is not
    expect((await ask(1, "initialize", handshake)).result.serverInfo.name).toBe("fake-http-mcp");
    child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);
    expect((await ask(2, "tools/list")).result.tools.map((tool: { name: string }) => tool.name)).toEqual(["report", "campaigns"]);
    expect(fake.initializes).toEqual([{ protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "OpenMausBot tool proxy", version: "1" } }]);
  });

  it("tells the engine to sign in again when the server refuses the token, with nothing remote", async () => {
    fake = await startFakeHttpMcp({ tools: TOOLS, acceptBearer: (authorization) => authorization === "Bearer fresh-token" });
    const descriptor = mcpStdioServer({ type: "http", url: fake.url, headers: { Authorization: "Bearer expired-token" } })!;
    child = spawn(descriptor.command, ["--experimental-strip-types", "--no-warnings", ...descriptor.args!], { stdio: "pipe", env: { ...process.env, ...descriptor.env } });
    const lines = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
    child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: CODEX })}\n`);
    const answer = JSON.parse((await lines.next()).value as string) as { error?: { message: string } };
    expect(answer.error?.message).toBe("This MCP server refused its sign-in (HTTP 401). Sign in to it again in Plugins → MCP servers, then retry.");
    expect(answer.error?.message).not.toContain(fake.url);
    // not Codex's own sign-in: codexSignInRefused reads "please sign in again"
    expect(answer.error?.message).not.toMatch(/please (?:log out and )?sign in again/i);
  });
});
