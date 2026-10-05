// @vitest-environment happy-dom
// Choosing Cloud computer, or opening the Computer panel, creates and wakes
// nothing: the bot's first computer call starts its cloud computer. The
// panel says so, and starting it now is the person's own button. A
// conversation that select_computer moved to an existing cloud computer
// still shows that computer's screen.
import { createElement } from "react";
import { flushSync } from "react-dom";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { AppState, Bot, InstanceInfo } from "@/state/store";

const fixture = vi.hoisted(() => ({
  boatState: null as string | null,
  posts: [] as string[],
}));
vi.mock("./DesktopCapabilities", async (importOriginal) => ({
  ...await importOriginal<typeof import("./DesktopCapabilities")>(),
  useCaptionChrome: () => ({ padClass: undefined }),
  useDesktopCapabilities: () => ({
    ready: true,
    capabilities: {
      host: { platform: "darwin", label: "Host", session: "unknown", packaged: true, homeDir: "/Users/me" },
      windowChrome: "native",
      screenPreview: { available: false, interaction: "none" },
      dictation: { available: false, engine: "none", onDevice: false },
      localComputer: { available: false, support: "unsupported", enabled: false, status: "unavailable" },
    },
  }),
}));
vi.mock("@/lib/interface-mode", () => ({ useAdvancedMode: () => true, setAdvancedMode: () => {} }));
vi.mock("./CloudScreenPreview", () => ({
  CloudScreenPreview: () => createElement("div", { "data-live-screen": "" }),
}));
vi.mock("./AndroidDevicePanel", () => ({ AndroidDevicePanel: () => null, useAndroidUsbDevices: () => ({ devices: [] }) }));
vi.mock("./BrowserPanel", () => ({ BrowserPanel: () => null }));
vi.mock("./CloudBackendPicker", () => ({ CloudBackendPicker: () => null }));
vi.mock("./bot-settings/RoutinesSection", () => ({ RoutinesSection: () => null }));
vi.mock("@/state/store", async (importOriginal) => ({
  ...await importOriginal<typeof import("@/state/store")>(),
  api: async (path: string, init?: RequestInit) => {
    if (init?.method === "POST") fixture.posts.push(path.split("?")[0]!);
    if (path.startsWith("/api/bots/scout/computer/provision")) {
      fixture.boatState = "ready";
      return { boxId: "bx_23456789", state: "ready" };
    }
    if (path.startsWith("/api/bots/scout/computer/screenshot")) return { png: "FRAME", format: "jpeg" };
    if (path.startsWith("/api/bots/scout/computer/control")) return { held: false, helpReason: null };
    if (path.startsWith("/api/bots/scout/computer?")) {
      return { surface: "cloud", backend: "box", configured: true,
        box: fixture.boatState ? { boxId: "bx_23456789", state: fixture.boatState } : null };
    }
    return {};
  },
}));

const { ComputerPanel } = await import("./ComputerPanel");
const { BotEditorStore, initialState } = await import("@/state/store");

const engine = {
  instanceId: "claude", driverKind: "claudeAgent", displayName: "Claude", access: "subscription",
  snapshot: { state: "available", version: "1", authenticated: true },
  models: { default: "m", options: [{ id: "m", label: "M" }] },
  capabilities: { computerMcp: true, browserMcp: true },
} as InstanceInfo;
const makeBot = (patch: Partial<Bot> = {}): Bot => ({
  id: "scout", threadId: "thread", name: "Scout", title: "", description: "", color: "green",
  notifications: true, unread: false, busy: false, messages: [],
  modelSelection: { instanceId: "claude", model: "m" },
  tasks: [{ threadId: "thread", title: "Thread", createdAt: 1, approvalMode: "ask" }],
  ...patch,
}) as unknown as Bot;

let root: Root | null = null;
const value = (): Parameters<typeof BotEditorStore>[0]["value"] => ({
  state: { ...initialState, instances: [engine], config: { box: { configured: true } } as AppState["config"] } as AppState,
  dispatch: vi.fn(),
  flushBotPatches: async () => null,
  refreshInstances: async () => {},
  refreshModels: async () => {},
});
const settle = async () => {
  for (let i = 0; i < 20; i++) await new Promise((resolve) => setTimeout(resolve, 0));
};
const open = async (bot: Bot) => {
  if (!root) {
    const container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
  }
  flushSync(() => root!.render(createElement(BotEditorStore, { value: value(), children: createElement(ComputerPanel, { bot }) })));
  await settle();
};
const text = () => document.body.textContent ?? "";
const button = (label: string) => [...document.querySelectorAll("button")].find((candidate) => candidate.textContent?.trim() === label);

beforeAll(() => {
  vi.stubGlobal("ogb", undefined);
});
afterEach(() => {
  root?.unmount();
  root = null;
  document.body.innerHTML = "";
  fixture.posts.length = 0;
  fixture.boatState = null;
});

describe("Computer panel on Cloud computer", () => {
  it("opening it before the bot has a cloud computer creates nothing and says when it starts", async () => {
    await open(makeBot({ computer: "cloud" }));
    expect(text()).toContain("Scout gets its own cloud computer the first time a task needs one. The first start takes about a minute.");
    expect(button("Start it now")).toBeDefined();
    expect(fixture.posts).toEqual([]);
  });

  it("opening it while the cloud computer sleeps wakes nothing", async () => {
    fixture.boatState = "archived";
    await open(makeBot({ computer: "cloud" }));
    expect(text()).toContain("Scout's cloud computer is asleep. It wakes in a few seconds when Scout needs it.");
    expect(button("Wake it now")).toBeDefined();
    expect(fixture.posts).toEqual([]);
  });

  it("choosing Cloud computer creates nothing either", async () => {
    await open(makeBot());
    await open(makeBot({ computer: "cloud" }));
    expect(button("Start it now")).toBeDefined();
    expect(fixture.posts).toEqual([]);
  });

  it("while the bot only chats, says its cloud computer starts when a task needs it", async () => {
    await open(makeBot({ computer: "cloud", busy: true }));
    expect(text()).toContain("Scout gets its own cloud computer the first time a task needs one.");
    expect(button("Start it now")).toBeUndefined();
    expect(fixture.posts).toEqual([]);
  });

  it("starts it only when the person presses Start it now", async () => {
    await open(makeBot({ computer: "cloud" }));
    flushSync(() => button("Start it now")!.click());
    await settle();
    // One start, then the screen it shows (screenshots never wake anything).
    expect(fixture.posts.filter((path) => !path.endsWith("/screenshot"))).toEqual(["/api/bots/scout/computer/provision"]);
    expect(document.querySelector("[data-live-screen]")).not.toBeNull();
  });

  it("says the same about a cloud computer this conversation was moved to, without a start button", async () => {
    // The bot works on Auto; select_computer moved this one conversation.
    const moved = { tasks: [{ threadId: "thread", title: "Thread", createdAt: 1, approvalMode: "ask", surface: "cloud", surfaceAuto: true }] } as Partial<Bot>;
    fixture.boatState = "archived";
    await open(makeBot(moved));
    expect(text()).toContain("Scout's cloud computer is asleep. It wakes in a few seconds when Scout needs it.");
    expect(button("Wake it now")).toBeUndefined();
    expect(fixture.posts).toEqual([]);
  });

  it("still shows the screen of a cloud computer that select_computer moved this conversation to", async () => {
    fixture.boatState = "ready";
    await open(makeBot({ tasks: [{ threadId: "thread", title: "Thread", createdAt: 1, approvalMode: "ask", surface: "cloud", surfaceAuto: true }] } as Partial<Bot>));
    expect(document.querySelector("[data-live-screen]")).not.toBeNull();
    expect(fixture.posts.filter((path) => !path.endsWith("/screenshot"))).toEqual([]);
  });
});
