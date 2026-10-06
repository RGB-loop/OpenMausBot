import { useEffect, useState, type ComponentType, type ReactNode } from "react";

// Screens that open only on request: Settings, Routines, the Team map, the
// Computer panel and the rest below. Imported directly, every launch would
// parse and run all of them before its first paint; each is its own chunk
// instead, fetched once the launch is idle (preloadScreens).
//
// Deliberately not React.lazy + Suspense. lazy() suspends on every first
// render, even when the chunk is already here, and React holds the commit
// after a fallback for up to 300 ms, so each first open would lag. It also
// rethrows a failed import on every render, and nothing above these screens
// catches errors, so one missing chunk would blank the whole app.

export type LazyScreen<P> = ((props: P) => ReactNode) & {
  preload: () => Promise<ComponentType<P>>;
};

// NonNullable: NewBotDialog takes its props as an optional parameter.
export function lazyScreen<Props>(load: () => Promise<(props: Props) => ReactNode>): LazyScreen<NonNullable<Props>> {
  type P = NonNullable<Props>;
  let ready: ComponentType<P> | undefined;
  let loading: Promise<ComponentType<P>> | undefined;
  // A failed load is forgotten, so the next request tries again.
  const preload = () => (loading ??= load().then(
    (component) => (ready = component as ComponentType<P>),
    (error: unknown) => {
      loading = undefined;
      throw error;
    },
  ));
  function Screen(props: P) {
    // Fetched already: render it in this same commit, as an ordinary import would.
    const [Loaded, setLoaded] = useState(() => ready);
    useEffect(() => {
      if (Loaded) return;
      let live = true;
      let retry: ReturnType<typeof setTimeout> | undefined;
      // Opened before its chunk arrived: nothing until it does. A failed
      // fetch (the server restarting) leaves just this screen empty and asks
      // again for as long as the screen is open.
      const attempt = (delay: number) => void preload().then(
        (component) => { if (live) setLoaded(() => component); },
        () => { if (live) retry = setTimeout(() => attempt(Math.min(delay * 2, 30_000)), delay); },
      );
      attempt(1_000);
      return () => {
        live = false;
        clearTimeout(retry);
      };
    }, [Loaded]);
    return Loaded ? <Loaded {...props} /> : null;
  }
  return Object.assign(Screen, { preload });
}

export const ActivityPanel = lazyScreen(async () => (await import("./ActivityPanel")).ActivityPanel);
export const BotSettingsDialog = lazyScreen(async () => (await import("./BotSettingsDialog")).BotSettingsDialog);
export const ComputerPanel = lazyScreen(async () => (await import("./ComputerPanel")).ComputerPanel);
export const InspectorPanel = lazyScreen(async () => (await import("./InspectorPanel")).InspectorPanel);
export const KeyboardShortcutsModal = lazyScreen(async () => (await import("./KeyboardShortcutsModal")).KeyboardShortcutsModal);
export const LocalVmWorkspace = lazyScreen(async () => (await import("./LocalVmWorkspace")).LocalVmWorkspace);
export const NewBotDialog = lazyScreen(async () => (await import("./NewBotDialog")).NewBotDialog);
export const RemoteAgentSettingsPanel = lazyScreen(async () => (await import("./RemoteAgentSettingsPanel")).RemoteAgentSettingsPanel);
export const RemoteDesktopPanel = lazyScreen(async () => (await import("./remote-desktop-panel")).RemoteDesktopPanel);
export const RoutinesPage = lazyScreen(async () => (await import("./RoutinesPage")).RoutinesPage);
export const SettingsModal = lazyScreen(async () => (await import("./SettingsModal")).SettingsModal);
export const TeamMapPage = lazyScreen(async () => (await import("./TeamMapPage")).TeamMapPage);
export const TriggersPanel = lazyScreen(async () => (await import("./TriggersPanel")).TriggersPanel);

const SCREENS = [
  ActivityPanel, BotSettingsDialog, ComputerPanel, InspectorPanel, KeyboardShortcutsModal, LocalVmWorkspace, NewBotDialog,
  RemoteAgentSettingsPanel, RemoteDesktopPanel, RoutinesPage, SettingsModal, TeamMapPage, TriggersPanel,
];

// A window opened onto Settings (?desktop-settings=…: the desktop app's
// Workspaces, Organization and Cloud windows, phone pairing) shows it at
// mount, before the launch is idle: fetch it now, beside the session check.
if (typeof location !== "undefined" && new URLSearchParams(location.search).has("desktop-settings")) {
  void SettingsModal.preload().catch(() => {});
}

/** Fetches every screen above once the launch has painted and gone idle, so
 * a later click renders it at once. Returns a cancel for the effect cleanup. */
export function preloadScreens(): () => void {
  const run = () => {
    for (const screen of SCREENS) void screen.preload().catch(() => {});
  };
  if (typeof requestIdleCallback === "function") {
    const handle = requestIdleCallback(run, { timeout: 2_000 });
    return () => cancelIdleCallback(handle);
  }
  // Safari has no requestIdleCallback.
  const timer = setTimeout(run, 1_000);
  return () => clearTimeout(timer);
}
