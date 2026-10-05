// The Computer engine is gone (decision D5): a whole turn ran on Boat's own
// agent, which has no AI sign-in on a Cloud. These pin the one-time move off
// it: the engine is never offered again, saved settings that name it move to
// the engine a new bot gets (the ready rule), and Works on is kept.
import { rmSync } from "node:fs";
import { beforeEach, describe, expect, it } from "vitest";

import { BUILT_IN_DRIVERS } from "./drivers/builtIn.ts";
import { computerEngineMoveText, removedComputerInstanceIds } from "./computer-engine-removal.ts";
import { DATA_DIR, instanceConfigs, persistableInstanceConfigs, type AppConfig } from "./config.ts";
import type { ModelCatalog, ProviderSnapshot } from "./contracts.ts";
import { readyToRun, selectReplacementModelSelection } from "./default-model-selection.ts";
import { Store } from "./store.ts";

const models = (id: string): ModelCatalog => ({ default: id, options: [{ id, label: id }] });
const engine = (instanceId: string, driverKind: string, snapshot: ProviderSnapshot, access?: "subscription" | "api" | "custom") =>
  ({ instanceId, driverKind, snapshot, models: models(`${instanceId}-model`), ...(access ? { access } : {}) });
const signedIn = { state: "available", authenticated: true } as const;
const signedOut = { state: "available", authenticated: false } as const;

describe("the Computer engine is not offered", () => {
  it("is in no built-in driver list and no default fleet", () => {
    expect(BUILT_IN_DRIVERS.map(driver => driver.driverKind)).not.toContain("boxAgent");
    const fleet = instanceConfigs({ box: { token: "box_fixture" } });
    expect(fleet).not.toHaveProperty("computer");
    expect(Object.values(fleet).map(entry => entry.driver)).not.toContain("boxAgent");
  });

  it("drops a saved fleet's entries for it, so the next save leaves them off disk", () => {
    const cfg: AppConfig = {
      box: { token: "box_fixture" },
      instances: {
        claude: { driver: "claudeAgent" },
        computer: { driver: "boxAgent", environment: { BOX_TOKEN: "box_instance" } },
        myBoat: { driver: "boxAgent", displayName: "My Boat" },
      },
    };
    for (const map of [instanceConfigs(cfg), persistableInstanceConfigs(cfg)]) {
      expect(Object.keys(map)).toContain("claude");
      expect(map).not.toHaveProperty("computer");
      expect(map).not.toHaveProperty("myBoat");
    }
    // The caller's saved map is never changed in place.
    expect(cfg.instances).toHaveProperty("computer");
  });

  it("names every id the engine had, and leaves an id the person reused for another engine", () => {
    expect([...removedComputerInstanceIds(undefined)]).toEqual(["computer"]);
    expect([...removedComputerInstanceIds({ claude: { driver: "claudeAgent" }, myBoat: { driver: "boxAgent" } })].sort())
      .toEqual(["computer", "myBoat"]);
    expect([...removedComputerInstanceIds({ computer: { driver: "claudeAgent" } })]).toEqual([]);
  });
});

describe("the engine a moved bot gets", () => {
  it("is the saved default when it can run a turn now", () => {
    const instances = [engine("claude", "claudeAgent", signedIn), engine("codex", "codex", signedIn)];
    expect(selectReplacementModelSelection(instances, { instanceId: "codex", model: "codex-model" }))
      .toEqual({ instanceId: "codex", model: "codex-model" });
  });

  it("skips a signed-out engine for one that can run, Claude first", () => {
    const instances = [engine("codex", "codex", signedIn), engine("claude", "claudeAgent", signedOut), engine("claude2", "claudeAgent", signedIn)];
    expect(selectReplacementModelSelection(instances)).toEqual({ instanceId: "claude2", model: "claude2-model" });
    // A signed-out saved default is passed over too.
    expect(selectReplacementModelSelection(instances, { instanceId: "claude", model: "claude-model" }))
      .toEqual({ instanceId: "claude2", model: "claude2-model" });
  });

  it("counts a custom endpoint as ready whatever its sign-in says", () => {
    const custom = engine("router", "claudeAgent", signedOut, "custom");
    expect(readyToRun(custom)).toBe(true);
    expect(selectReplacementModelSelection([engine("codex", "codex", signedOut), custom]))
      .toEqual({ instanceId: "router", model: "router-model" });
  });

  it("falls back to an available engine, and is empty only when none is", () => {
    expect(selectReplacementModelSelection([engine("codex", "codex", signedOut)])).toEqual({ instanceId: "codex", model: "codex-model" });
    expect(selectReplacementModelSelection([engine("codex", "codex", { state: "unavailable", reason: "not installed" })]))
      .toEqual({ instanceId: "", model: "" });
  });

  it("never picks an engine the organisation refuses", () => {
    const instances = [engine("claude", "claudeAgent", signedIn), engine("codex", "codex", signedIn)];
    expect(selectReplacementModelSelection(instances, undefined, { refusal: instance => instance.instanceId === "claude" ? "not allowed" : undefined }))
      .toEqual({ instanceId: "codex", model: "codex-model" });
  });
});

describe("moving saved bots off the engine", () => {
  beforeEach(() => { rmSync(DATA_DIR, { recursive: true, force: true }); });
  const newBotDefault = () => ({ instanceId: "claude", model: "claude-model" });
  const replacement = { instanceId: "claude", model: "claude-model" };

  it("moves the bot's model, each conversation's model and its backups once, and keeps Works on", () => {
    const store = new Store(newBotDefault);
    const bot = store.createBot({ modelSelection: { instanceId: "computer", model: "claude-fable-5" } }, { seedMessages: false });
    const other = store.createBot({ modelSelection: { instanceId: "codex", model: "codex-model" } }, { seedMessages: false });
    store.patchBot(bot.id, { computer: "cloud", fallback: [{ instanceId: "computer", model: "sonnet" }, { instanceId: "codex", model: "codex-model" }] });
    const second = store.createTask(bot.id, "Second")!;
    store.patchTask(bot.id, second.threadId, { modelSelection: { instanceId: "computer", model: "gpt-5.4" } });
    store.setResumeCursor(bot.id, "computer", "boat-prompt-run", bot.threadId);
    store.setResumeCursor(bot.id, "codex", "codex-session", bot.threadId);

    expect(store.retireInstances(new Set(["computer"]), replacement)).toEqual([bot.id]);

    const reloaded = new Store(newBotDefault);
    const moved = reloaded.bot(bot.id)!;
    expect(moved.modelSelection).toEqual(replacement);
    expect(moved.computer).toBe("cloud");
    expect(moved.fallback).toEqual([{ instanceId: "codex", model: "codex-model" }]);
    expect(reloaded.taskByThread(bot.id, second.threadId)?.modelSelection).toEqual(replacement);
    expect(reloaded.taskByThread(bot.id, bot.threadId)?.resumeCursors).toEqual({ codex: "codex-session" });
    expect(reloaded.bot(other.id)?.modelSelection).toEqual({ instanceId: "codex", model: "codex-model" });
    // One time: nothing left names the engine.
    expect(reloaded.retireInstances(new Set(["computer"]), replacement)).toEqual([]);
  });

  it("says what happened in plain words", () => {
    expect(computerEngineMoveText("Scout", "Claude")).toBe(
      "Scout now uses Claude, because the Computer engine was removed. Where Scout works is unchanged.");
  });
});
