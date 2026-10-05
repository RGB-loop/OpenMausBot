// @vitest-environment happy-dom
// J10 through the real app store: Set up My Cloud's "Give Maus a cloud
// computer", clicked on a Cloud that offers the plan's cloud computers, with
// the Computer panel mounted wherever App mounts it (state.computerOpen). The
// click saves Works on: Cloud computer through the store's own patch queue and
// asks for nothing else: no computer is created or woken (no provision call),
// so choosing it costs no plan hours. Every HTTP request is an offline fixture.
import { createElement, Fragment } from "react";
import { flushSync } from "react-dom";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, beforeAll, expect, it, vi } from "vitest";
import type { BotAnnouncement, InstanceInfo } from "@/state/store";

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
vi.mock("@/lib/interface-mode", () => ({ useAdvancedMode: () => false, setAdvancedMode: () => {} }));
vi.mock("./CloudScreenPreview", () => ({ CloudScreenPreview: () => null }));
vi.mock("./AndroidDevicePanel", () => ({ AndroidDevicePanel: () => null, useAndroidUsbDevices: () => ({ devices: [] }) }));
vi.mock("./BrowserPanel", () => ({ BrowserPanel: () => null }));
vi.mock("./CloudBackendPicker", () => ({ CloudBackendPicker: () => null }));
vi.mock("./bot-settings/RoutinesSection", () => ({ RoutinesSection: () => null }));

class FixtureEventSource {
  static opened: FixtureEventSource[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: { data: string; lastEventId?: string }) => void) | null = null;
  constructor(readonly url: string) { FixtureEventSource.opened.push(this); }
  close() {}
  send(frame: object, id?: string) { this.onmessage?.({ data: JSON.stringify(frame), lastEventId: id }); }
}

const bot: BotAnnouncement = {
  id: "b1", threadId: "t1", name: "Maus", title: "", description: "",
  notifications: true, unread: false, color: "green",
  modelSelection: { instanceId: "claude", model: "m" },
  tasks: [{ threadId: "t1", title: "Thread", createdAt: 1, approvalMode: "ask" }],
};
const claude = {
  instanceId: "claude", driverKind: "claudeAgent", displayName: "Claude", access: "subscription",
  snapshot: { state: "available", version: "1", authenticated: true },
  models: { default: "m", options: [{ id: "m", label: "M" }] },
  capabilities: { computerMcp: true, browserMcp: true },
} as unknown as InstanceInfo;
const answers: Record<string, unknown> = {
  "/api/instances": { instances: [claude] },
  // A Cloud home with the plan's cloud computers, whose first plain turn already finished.
  "/api/config": {
    cloudHome: true, box: { configured: true, included: true },
    onboarding: { completedAt: "", version: 0, reelSeen: false, hintsSeen: [], firstTurnAt: "2026-09-30T08:00:00.000Z" },
  },
  "/api/routines": { routines: [], runs: [] },
  "/api/webhooks": { webhooks: [], attempts: [] },
  "/api/shared-computers": { computers: [] },
};
const requests: Array<{ path: string; method: string; body?: unknown }> = [];
const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body)));

const { StoreProvider, useStore } = await import("@/state/store");
const { CloudSetup } = await import("./CloudSetup");
const { ComputerPanel } = await import("./ComputerPanel");

/** What App mounts for this: the checklist, and the Computer panel whenever it is open. */
function Window() {
  const { state } = useStore();
  const selected = state.bots.find((candidate) => candidate.id === state.selectedId);
  return createElement(Fragment, null,
    createElement(CloudSetup, { viewer: { hosted: false, canSave: true, cloudHome: true } }),
    state.computerOpen && selected ? createElement(ComputerPanel, { key: `computer:${selected.id}`, bot: selected }) : null);
}

let root: Root;
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const settle = async () => { for (let i = 0; i < 8; i++) await wait(0); };
const button = (label: string) => [...document.querySelectorAll("button")].find((candidate) => candidate.textContent === label);

beforeAll(async () => {
  vi.stubGlobal("EventSource", FixtureEventSource);
  vi.stubGlobal("fetch", (input: string, init?: RequestInit) => {
    const path = String(input), method = init?.method ?? "GET";
    requests.push({ path, method, ...(init?.body ? { body: JSON.parse(String(init.body)) } : {}) });
    if (path.startsWith("/api/bots?")) return json({ bots: [bot], groups: [] });
    if (path === "/api/bots/b1" && method === "PATCH") return json({ bot: { ...bot, ...JSON.parse(String(init?.body)) } });
    if (path.startsWith("/api/bots/b1/computer?")) return json({ configured: true, box: null });
    if (path === "/api/bots/b1/computer/provision") return json({ state: "ready" });
    return json(answers[path] ?? {});
  });
  const container = document.createElement("div");
  document.body.append(container);
  root = createRoot(container);
  flushSync(() => root.render(createElement(StoreProvider, null, createElement(Window))));
  await settle();
  const stream = FixtureEventSource.opened[0]!;
  stream.onopen?.();
  stream.send({ kind: "hello", resumed: false, cursor: "run:0" });
  await settle();
});
afterAll(() => {
  root.unmount();
  vi.unstubAllGlobals();
});

it("J10: Give Maus a cloud computer saves Works on: Cloud computer and starts no computer", async () => {
  const give = button("Give Maus a cloud computer");
  expect(give, document.body.innerHTML).toBeTruthy();
  requests.length = 0;
  flushSync(() => give!.click());
  // The store's patch queue saves after its short pause; the panel, if it
  // were open, would have asked for its computer by then.
  await wait(700);
  await settle();
  expect(requests.filter((request) => request.method !== "GET")).toEqual([
    { path: "/api/bots/b1", method: "PATCH", body: { computer: "cloud" } },
  ]);
  expect(requests.map((request) => request.path).filter((path) => /\/computer\b|boat|box/.test(path))).toEqual([]);
  // The step now offers something to ask Maus; it is done only by a finished turn.
  expect(document.body.textContent).toContain("Maus now works on its cloud computer.");
  expect(document.querySelector('[data-cloud-setup-step="computer"]')?.getAttribute("data-status")).toBe("todo");
});
