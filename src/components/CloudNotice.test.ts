// J12: This computer's window and My Cloud. Renderer only, with a stub of the
// desktop's Cloud account bridge; no Electron.
import { Children, createElement, isValidElement, type EffectCallback, type ReactElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { CloudAccountBridge, CloudAccountState } from "../../electron/cloud-account.mjs";
import { setLocale } from "@/lib/i18n";
import { EMPTY_ONBOARDING } from "@/lib/onboarding";

const f = vi.hoisted(() => ({ values: [] as unknown[], index: 0, effects: [] as EffectCallback[], state: {} as any, updater: null as any, dispatched: [] as unknown[] }));
vi.mock("react", async original => ({ ...await original<typeof import("react")>(),
  useState: (initial: unknown) => { const index = f.index++; if (!(index in f.values)) f.values[index] = typeof initial === "function" ? (initial as () => unknown)() : initial;
    return [f.values[index], (next: unknown) => { f.values[index] = typeof next === "function" ? (next as (value: unknown) => unknown)(f.values[index]) : next; }]; },
  useEffect: (effect: EffectCallback) => { f.effects.push(effect); },
}));
vi.mock("@/state/store", () => ({ useStore: () => ({ state: f.state, dispatch: (action: unknown) => f.dispatched.push(action) }), api: vi.fn() }));
vi.mock("@/lib/updater", () => ({ useUpdaterState: () => f.updater }));
import { CLOUD_NOTICE_DISMISSED, CloudNotice } from "./CloudNotice";
import { proOfferAvailable } from "./ProIntroduction";
import { api } from "@/state/store";

type Node = ReactElement<{ children?: ReactNode; onClick?: () => void; "aria-label"?: string }>;
const nodes = (value: ReactNode): Node[] => !isValidElement(value) ? [] : [value as Node, ...Children.toArray((value as Node).props.children).flatMap(nodes)];
const text = (node: Node): string => Children.toArray(node.props.children).map(child => typeof child === "string" ? child : isValidElement(child) ? text(child as Node) : "").join("");
function render(quiet = false) {
  f.index = 0; f.effects = []; let tree: ReactNode;
  function Capture() { tree = CloudNotice({ quiet }); return tree; }
  const html = renderToStaticMarkup(createElement(Capture));
  return { html, nodes: nodes(tree) };
}
const button = (label: string) => render().nodes.find(node => node.type === "button" && (text(node) === label || node.props["aria-label"] === label));
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

const account = { id: "a", email: "person@example.test" };
const origin = "https://omb-u-1a2b3c4d5e6f.fly.dev";
const myCloudReady: CloudAccountState = { status: "connected", account, entitlement: { plan: "pro", tier: "personal", status: "active", expiresAt: 1_900_000_000_000, version: 2 }, machine: { status: "ready", origin } };
const free: CloudAccountState = { status: "connected", account, entitlement: { plan: "free", status: "inactive", expiresAt: null, version: 1 } };
let bridge: CloudAccountBridge, push: (state: CloudAccountState) => void, storage: Map<string, string>;

async function show(state: CloudAccountState) { vi.mocked(bridge.state).mockResolvedValueOnce(state); render(); for (const effect of f.effects) effect(); await flush(); }

beforeEach(() => {
  vi.clearAllMocks(); f.values = []; f.index = 0; f.effects = []; f.updater = null; f.dispatched = []; storage = new Map(); push = () => {};
  f.state = { connected: true, config: { onboarding: { ...EMPTY_ONBOARDING, completedAt: "2026-09-01", version: 1 } } };
  bridge = {
    state: vi.fn().mockResolvedValue({ status: "signed-out" }), begin: vi.fn().mockResolvedValue({ status: "connecting" }), signInAgain: vi.fn().mockResolvedValue({ status: "connecting" }),
    reopen: vi.fn(), cancel: vi.fn(), refresh: vi.fn(), signOut: vi.fn(), openDashboard: vi.fn(),
    connectHome: vi.fn().mockResolvedValue(myCloudReady), connectHomeForPhone: vi.fn(),
    onState: vi.fn(callback => { push = callback; return () => {}; }),
  };
  vi.stubGlobal("window", { ogb: { cloudAccount: bridge } });
  vi.stubGlobal("localStorage", { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => storage.set(key, value) });
  vi.mocked(api).mockImplementation(async (_path: string, init?: RequestInit) => ({ ...f.state.config, onboarding: { ...f.state.config.onboarding, ...JSON.parse(String(init?.body)).onboarding } }));
  setLocale("en");
});
afterEach(() => { vi.unstubAllGlobals(); setLocale("en"); });

it("J12: a paid plan on This computer gets one card pointing to My Cloud, and Open My Cloud opens it once", async () => {
  await show(myCloudReady);
  const { html } = render();
  expect(html).toContain("Your always-on bots are on My Cloud");
  expect(html).toContain("Bots on this computer stop when it sleeps or the app is closed. Bots on My Cloud keep working, and can use cloud computers.");
  expect(render().nodes.filter(node => node.type === "button").map(text).filter(Boolean)).toEqual(["Open My Cloud"]);
  button("Open My Cloud")!.props.onClick!(); await flush();
  expect(bridge.connectHome).toHaveBeenCalledExactlyOnceWith();
  // Nothing else is asked of the account: no sign-in, no refresh, no browser.
  for (const method of ["begin", "signInAgain", "refresh", "openDashboard"] as const) expect(bridge[method]).not.toHaveBeenCalled();
  expect(html).not.toMatch(/OMB|Boat|Get Pro|\$\d/);
});

it("J12: My Cloud not opening says so in one line, and can be tried again", async () => {
  vi.mocked(bridge.connectHome).mockRejectedValueOnce(new Error("offline"));
  await show(myCloudReady);
  button("Open My Cloud")!.props.onClick!(); await flush();
  expect(render().html).toContain("My Cloud didn&#x27;t open. Try again in a minute.");
  button("Open My Cloud")!.props.onClick!(); await flush();
  expect(bridge.connectHome).toHaveBeenCalledTimes(2);
  expect(render().html).not.toContain("didn&#x27;t open");
});

it("J12: Not now hides the My Cloud card for good, kept in this computer's settings", async () => {
  await show(myCloudReady);
  button("Not now")!.props.onClick!(); await flush();
  expect(render().html).toBe("");
  expect(api).toHaveBeenCalledWith("/api/config", { method: "PUT", body: JSON.stringify({ onboarding: { hintsSeen: [CLOUD_NOTICE_DISMISSED] } }) });
  // Another window or cleared browser storage: the record decides.
  f.values = []; storage.clear(); f.state.config.onboarding = { ...f.state.config.onboarding, hintsSeen: [CLOUD_NOTICE_DISMISSED] };
  await show(myCloudReady);
  expect(render().html).toBe("");
});

it("J12: a removed or ended sign-in gets Sign in again instead of silence, and no offer to buy", async () => {
  await show({ status: "signed-out", message: "restore-removed" });
  const { html } = render();
  expect(html).toContain("Sign in again to reach My Cloud");
  expect(html).toContain("Your plan and your bots there are not affected.");
  expect(proOfferAvailable({ status: "signed-out", message: "restore-removed" })).toBe(false);
  button("Sign in again")!.props.onClick!(); await flush();
  expect(bridge.begin).toHaveBeenCalledExactlyOnceWith();
  // A sign-in that ended is forgotten and started again.
  f.values = []; await show({ status: "reauth-required", message: "expired", lastPlan: { tier: "max", active: true } });
  expect(render().html).toContain("Sign in again to reach My Cloud");
  button("Sign in again")!.props.onClick!(); await flush();
  expect(bridge.signInAgain).toHaveBeenCalledExactlyOnceWith();
  // Signing in is under way: the card steps aside.
  expect(render().html).toBe("");
  // The browser was closed without finishing: Not now hides it for now; it
  // comes back next time the app opens.
  push({ status: "reauth-required", message: "expired", lastPlan: { tier: "max", active: true } });
  button("Not now")!.props.onClick!();
  expect(render().html).toBe("");
  expect(api).not.toHaveBeenCalled();
});

it("J12: signed out or free, this card says nothing: the existing offer to buy is the one card there", async () => {
  for (const state of [{ status: "signed-out" }, free] as CloudAccountState[]) {
    f.values = []; await show(state);
    expect(render().html, JSON.stringify(state)).toBe("");
    expect(proOfferAvailable(state)).toBe(true);
  }
  // My Cloud still being set up is shown in Settings, not here.
  f.values = []; await show({ ...myCloudReady, machine: { status: "provisioning" } });
  expect(render().html).toBe("");
});

it("J12: on My Cloud, and on any server open in this window, nothing shows", async () => {
  // A server's page gets no Cloud account bridge.
  vi.stubGlobal("window", { ogb: {} });
  f.state.config = { ...f.state.config, cloudHome: true };
  render(); for (const effect of f.effects) effect(); await flush();
  expect(render().html).toBe("");
  vi.stubGlobal("window", { ogb: { cloudAccount: bridge, remoteClient: { active: true } } });
  f.values = []; render(); for (const effect of f.effects) effect(); await flush();
  expect(render().html).toBe("");
  expect(bridge.state).not.toHaveBeenCalled();
});

it("waits while Settings, the welcome, the tour or an update is on screen", async () => {
  await show(myCloudReady);
  for (const busy of [{ appSettingsOpen: true }, { welcomeOpen: true }, { tourOpen: true }, { connected: false }]) {
    const saved = f.state; f.state = { ...f.state, ...busy };
    expect(render().html, JSON.stringify(busy)).toBe("");
    f.state = saved;
  }
  f.updater = { status: "available" }; expect(render().html).toBe(""); f.updater = null;
  expect(render(true).html).toBe("");
  expect(render().html).toContain("Your always-on bots are on My Cloud");
});
