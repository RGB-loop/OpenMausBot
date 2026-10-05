// This computer's one card about My Cloud (docs/cloud-pro.md, "This computer
// and My Cloud"), bottom left where the Pro introduction sits for someone who
// may buy. Which card, if any, comes from the plan's one state-to-view
// function (lib/cloud-plan cloudNoticeKind):
// - a paid plan whose My Cloud is ready: the always-on bots are there, with
//   Open My Cloud, which opens it in this window;
// - a sign-in that ended, or one this computer could not read and removed:
//   Sign in again, instead of silence.
// One message and one action each, no dialog. Only This computer's own page
// has the Cloud account bridge, so a server open in this window (My Cloud
// included) never shows it, and neither does a browser.
import { useEffect, useState } from "react";
import { Cloud, X } from "lucide-react";
import type { CloudAccountState } from "../../electron/cloud-account.mjs";
import { cloudNoticeKind, cloudPlanView } from "@/lib/cloud-plan";
import { t } from "@/lib/i18n";
import { hintSeen, hintSeenPatch } from "@/lib/onboarding";
import { useUpdaterState } from "@/lib/updater";
import { api, useStore } from "@/state/store";

/** Not now on the My Cloud card: in this computer's onboarding record (and
 * this browser's storage, at once), so it does not come back. */
export const CLOUD_NOTICE_DISMISSED = "cloud-notice-my-cloud-dismissed";

export function CloudNotice({ quiet = false }: { quiet?: boolean }) {
  const { state, dispatch } = useStore();
  const bridge = window.ogb?.remoteClient?.active ? undefined : window.ogb?.cloudAccount;
  const [account, setAccount] = useState<CloudAccountState | null>(null);
  const [dismissed, setDismissed] = useState(() => {
    try { return localStorage.getItem(CLOUD_NOTICE_DISMISSED) === "1"; } catch { return false; }
  });
  // Sign in again is about a problem, so Not now lasts until the app opens again.
  const [later, setLater] = useState(false);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const updater = useUpdaterState();
  useEffect(() => {
    if (!bridge) return;
    let active = true, updated = false;
    const unsubscribe = bridge.onState(next => { updated = true; if (active) setAccount(next); });
    // Reads the native snapshot only; never signs in or refreshes by itself.
    void bridge.state().then(next => { if (active && !updated) setAccount(next); }).catch(() => {});
    return () => { active = false; unsubscribe(); };
  }, [bridge]);
  if (!bridge) return null;
  const kind = cloudNoticeKind(cloudPlanView(account), account);
  const record = state.config?.onboarding;
  if (!kind || quiet || !state.connected || !state.config
    || state.welcomeOpen || state.tourOpen || state.appSettingsOpen
    || (updater && !["idle", "checking"].includes(updater.status))) return null;
  if (kind === "my-cloud" && (dismissed || hintSeen(record, CLOUD_NOTICE_DISMISSED))) return null;
  if (kind === "sign-in-again" && later) return null;

  const notNow = () => {
    if (kind === "sign-in-again") { setLater(true); return; }
    setDismissed(true);
    try { localStorage.setItem(CLOUD_NOTICE_DISMISSED, "1"); } catch { /* the record below is the durable one */ }
    const patch = hintSeenPatch(record, CLOUD_NOTICE_DISMISSED);
    if (patch) void api("/api/config", { method: "PUT", body: JSON.stringify(patch) })
      .then(config => dispatch({ type: "configStatus", config })).catch(() => {});
  };
  const act = () => {
    if (busy) return;
    setFailed(false); setBusy(true);
    const step = kind === "my-cloud" ? bridge.connectHome()
      : account?.status === "reauth-required" ? bridge.signInAgain() : bridge.begin();
    void step.then(next => setAccount(next), () => setFailed(true)).finally(() => setBusy(false));
  };
  const myCloud = kind === "my-cloud";
  return <aside aria-labelledby="cloud-notice-title" data-cloud-notice={kind} onKeyDown={event => {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); notNow(); }
  }} className="fixed bottom-4 left-4 z-40 max-h-[calc(100dvh-32px)] w-[300px] max-w-[calc(100vw-32px)] overflow-y-auto rounded-xl border border-hairline/40 bg-panel p-3.5 text-ink shadow-2xl shadow-black/20">
    <div className="flex items-start justify-between gap-3">
      <div className="flex items-center gap-2.5">
        <Cloud size={16} className="shrink-0 text-ink-secondary" aria-hidden="true" />
        <h2 id="cloud-notice-title" className="text-[13.5px] font-semibold">{t(myCloud ? "cloudNotice.myCloud.title" : "cloudNotice.signIn.title")}</h2>
      </div>
      <button type="button" onClick={notNow} aria-label={t("cloudNotice.notNow")} className="ui-icon-button"><X size={16} /></button>
    </div>
    <p className="mt-1 text-[12.5px] leading-relaxed text-ink-secondary">{t(myCloud ? "cloudNotice.myCloud.hint" : "cloudNotice.signIn.hint")}</p>
    <button type="button" disabled={busy} className="ui-button mt-3" onClick={act}>{t(myCloud ? "cloudNotice.myCloud.action" : "cloudAccount.signInAgain")}</button>
    {failed && <p role="alert" className="mt-2 text-[12px] text-danger">{t(myCloud ? "cloudNotice.openFailed" : "cloudAccount.actionFailed")}</p>}
  </aside>;
}
