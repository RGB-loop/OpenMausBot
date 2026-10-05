// The Computer engine is gone (decision D5). It ran a whole turn on Boat's own
// agent through /boxes/{id}/prompt, and that agent has no AI sign-in on a
// Cloud: the hosted-desktop bug. A bot saved on it now moves, once, to the
// engine a new bot gets and keeps its Works on; every turn runs on that
// engine with the cloud computer as a tool, and an Auto turn never reads the
// Boat account at all. Real server, fake Claude CLI, and a loopback Boat that
// fails the test on any call to Boat's own agent (/prompt, /prompts/{id},
// /events, /interrupt).
import { spawn, type ChildProcess } from "node:child_process";
import { createServer, type Server } from "node:http";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { runControlOmb } from "../scripts/control-omb.ts";
import { computerEngineMoveText } from "./computer-engine-removal.ts";
import { freePortBlock } from "./testing/ports.ts";
import { removeTempDir, waitForExit } from "./testing/cleanup.ts";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const ENGINE_NAME = "Verification Claude";
const JPEG = Buffer.from("/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q==", "base64");

describe("removing the Computer engine", () => {
  let home = "";
  let data = "";
  let dumpFile = "";
  let output = "";
  let child: ChildProcess | null = null;
  let base = "";
  let boatServer: Server;
  const rows: Array<{ id: string; name: string; state: string }> = [];
  /** Every request to the Boat account, reads included. */
  let boatCalls = 0;
  let boxesCreated = 0;
  /** Calls to Boat's own agent: the place no turn may ever go. */
  const agentCalls: string[] = [];

  const api = async (method: string, path: string, body?: unknown) => {
    const response = await fetch(base + path, {
      method, headers: { "content-type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const result = await response.json() as any;
    expect(response.status, `${method} ${path}: ${JSON.stringify(result)}`).toBeLessThan(400);
    return result;
  };
  const control = (args: string[]) => runControlOmb([...args, "--url", base]) as Promise<any>;
  const savedBots = (): any[] => JSON.parse(readFileSync(join(data, "bots.json"), "utf8"));
  const editSavedBot = (botId: string, edit: (bot: any) => void) => {
    const bots = savedBots();
    edit(bots.find(bot => bot.id === botId));
    writeFileSync(join(data, "bots.json"), JSON.stringify(bots, null, 2));
  };
  const activityLines = async (threadId: string): Promise<string[]> =>
    (await api("GET", `/api/threads/${threadId}/messages?limit=50`)).messages
      .filter((message: any) => message.kind === "activity").map((message: any) => String(message.tool?.name));
  const dump = async () => {
    let parsed: any = null;
    await expect.poll(() => {
      if (!existsSync(dumpFile)) return false;
      try { parsed = JSON.parse(readFileSync(dumpFile, "utf8")); return true; } catch { return false; }
    }, { timeout: 15_000 }).toBe(true);
    return parsed;
  };
  const turn = async (botId: string, threadId: string, text: string) => {
    rmSync(dumpFile, { force: true });
    await control(["send", "--bot", botId, "--task", threadId, "--text", text]);
    const settled = await control(["wait", "--bot", botId, "--task", threadId, "--timeout", "30"]);
    expect(settled.status, JSON.stringify(settled)).toBe("settled");
    return dump();
  };

  async function start() {
    const port = await freePortBlock([0, 1]);
    base = `http://127.0.0.1:${port}`;
    output = "";
    const proc = spawn(process.execPath, [join(ROOT, "server/index.ts")], {
      cwd: ROOT, env: {
        PATH: dirname(process.execPath), ...(process.env.SystemRoot ? { SystemRoot: process.env.SystemRoot } : {}),
        HOME: home, USERPROFILE: home, OMB_DATA_DIR: data,
        APPDATA: join(home, "appdata"), LOCALAPPDATA: join(home, "localappdata"),
        TEMP: home, TMP: home, TMPDIR: home,
        OMB_PORT: String(port), OMB_WEBHOOK_PORT: String(port + 1), OMB_STATIC_DIR: join(home, "static"),
        OMB_BOX_API: `http://127.0.0.1:${(boatServer.address() as { port: number }).port}`,
        OMB_USER_DATA: join(home, "user-data"),
      }, stdio: ["ignore", "pipe", "pipe"],
    });
    child = proc;
    proc.stdout!.on("data", chunk => { output += chunk; });
    proc.stderr!.on("data", chunk => { output += chunk; });
    await expect.poll(async () => {
      if (proc.exitCode !== null) throw new Error(`server exited during boot:\n${output}`);
      try { return (await fetch(base + "/api/health")).ok; } catch { return false; }
    }, { timeout: 20_000, interval: 50 }).toBe(true);
  }
  async function stop() {
    if (!child) return;
    const proc = child;
    child = null;
    await waitForExit(proc, { signal: "SIGTERM" });
  }

  beforeAll(async () => {
    home = mkdtempSync(join(tmpdir(), "omb-computer-engine-"));
    data = join(home, "data");
    dumpFile = join(home, "dump.json");
    mkdirSync(data);
    mkdirSync(join(home, "static", "assets"), { recursive: true });
    writeFileSync(join(home, "static", "index.html"), "<title>Computer engine removal</title>");
    writeFileSync(join(home, "static", "assets", "test.css"), "body{}");
    boatServer = createServer(async (req, res) => {
      const path = new URL(req.url ?? "/", "http://box.fixture").pathname;
      let raw = ""; for await (const part of req) raw += part;
      const body = raw ? JSON.parse(raw) : {};
      res.setHeader("content-type", "application/json");
      boatCalls++;
      if (/\/(prompt|events|interrupt)$|\/prompts\//.test(path)) {
        agentCalls.push(`${req.method} ${path}`);
        res.statusCode = 409;
        return res.end(JSON.stringify({ ok: false, code: "provider_not_configured", message: "Claude login required" }));
      }
      if (path === "/boxes" && req.method === "POST") {
        boxesCreated++;
        const row = { id: "bx_23456789", name: body.name, state: "idle" }; rows.push(row);
        return res.end(JSON.stringify({ box: row }));
      }
      if (path === "/boxes") return res.end(JSON.stringify({ boxes: rows }));
      if (path.endsWith("/commands")) return res.end(JSON.stringify({ exitCode: 0, stdout: "captured", stderr: "" }));
      if (path.endsWith("/artifacts")) { res.setHeader("content-type", "image/jpeg"); return res.end(JPEG); }
      if (path.endsWith("/desktop")) return res.end(JSON.stringify({ desktopUrl: "https://desktop.fixture.invalid" }));
      const row = rows.find(entry => path === "/boxes/" + entry.id);
      if (row) {
        if (req.method === "PATCH" && typeof body.name === "string") row.name = body.name;
        return res.end(JSON.stringify({ box: row }));
      }
      res.end("{}");
    });
    await new Promise<void>(resolve => boatServer.listen(0, "127.0.0.1", resolve));
    // A fleet saved before the removal: the Computer engine next to Claude.
    writeFileSync(join(data, "config.json"), JSON.stringify({
      box: { token: "box_verification_fixture" },
      instances: {
        claude: {
          driver: "claudeAgent", displayName: ENGINE_NAME,
          config: { cli: join(ROOT, "server/testing/fake-claude-cli.ts") },
          environment: { FAKE_CLAUDE_DUMP: dumpFile },
        },
        computer: { driver: "boxAgent" },
      },
    }));
  });
  afterAll(async () => {
    await stop();
    if (boatServer) { boatServer.closeAllConnections(); await new Promise<void>(resolve => boatServer.close(() => resolve())); }
    if (home) await removeTempDir(home);
  });

  it("moves a bot off it once, keeps Works on, and never hands a turn to Boat's own agent", async () => {
    await start();
    const [starter] = (await api("GET", "/api/bots?messages=0")).bots;
    const { task: second } = await api("POST", `/api/bots/${starter.id}/tasks`, {});
    await stop();
    // The bot as v0.1.94 saved it: the Computer engine, Works on Cloud, a
    // conversation of its own on the engine, a backup on it, and Boat's run id.
    editSavedBot(starter.id, saved => {
      saved.modelSelection = { instanceId: "computer", model: "claude-fable-5" };
      saved.computer = "cloud";
      saved.fallback = [{ instanceId: "computer", model: "sonnet" }];
      saved.resumeCursors = { computer: "boat-prompt-run" };
      saved.tasks.find((task: any) => task.threadId === second.threadId).modelSelection = { instanceId: "computer", model: "gpt-5.4" };
    });

    await start();
    // The deleted model id is not offered.
    const { instances } = await api("GET", "/api/instances");
    expect(instances.map((instance: any) => instance.instanceId)).not.toContain("computer");
    expect(instances.map((instance: any) => instance.driverKind)).not.toContain("boxAgent");
    const claude = instances.find((instance: any) => instance.instanceId === "claude");
    // The bot moved to the engine a new bot gets, and kept Works on.
    const replacement = { instanceId: "claude", model: claude.models.default };
    const bot = (await api("GET", "/api/bots?messages=0")).bots.find((entry: any) => entry.id === starter.id);
    expect(bot.modelSelection).toEqual(replacement);
    expect(bot.computer).toBe("cloud");
    expect(bot.fallback ?? []).toEqual([]);
    expect(bot.tasks.find((task: any) => task.threadId === second.threadId).modelSelection).toEqual(replacement);
    // Its conversation says so, once, in plain words.
    const line = computerEngineMoveText(bot.name, ENGINE_NAME);
    expect((await activityLines(bot.threadId)).filter(name => name === line)).toHaveLength(1);
    await stop();
    await start();
    expect((await activityLines(bot.threadId)).filter(name => name === line)).toHaveLength(1);

    // Works on: Cloud runs on the bot's own engine, with the cloud computer as
    // a tool; Boat's own agent is never asked.
    const cloudTurn = await turn(bot.id, bot.threadId, "Take a screenshot of the cloud computer.");
    expect(cloudTurn.argv[cloudTurn.argv.indexOf("--model") + 1]).toBe(replacement.model);
    expect(cloudTurn.mcpConfig.mcpServers.computer.args).toEqual([expect.stringMatching(/harness-mcp-proxy\.(?:ts|js)$/), "computer"]);
    expect(boxesCreated).toBe(1);
    expect(agentCalls).toEqual([]);

    // Auto, with this bot's cloud computer running: zero Boat calls of any
    // kind (the removed engine was the one case where Auto attached a Boat).
    await api("PATCH", `/api/bots/${bot.id}`, { computer: null });
    const callsBeforeAuto = boatCalls;
    const autoTurn = await turn(bot.id, second.threadId, "what is 2+2?");
    expect(autoTurn.mcpConfig?.mcpServers?.computer?.args?.[1]).not.toBe("computer");
    expect(boatCalls - callsBeforeAuto, "an Auto turn reached the Boat account").toBe(0);
    expect(agentCalls).toEqual([]);
    await stop();
  }, 120_000);
});
