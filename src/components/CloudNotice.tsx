// This computer's one card about My Cloud (docs/cloud-pro.md, "This computer
// and My Cloud"), bottom left where the Pro introduction sits for someone who
// may buy. Which card, if any, comes from the plan's one state-to-view
// function (lib/cloud-plan cloudNoticeKind), and its one action is the
// state's cloudPlanAction, named and done as Settings does:
// - a paid plan whose My Cloud is ready: the always-on bots are there, with
//   Open My Cloud, which opens it in this window;
// - a sign-in that ended, on a plan this computer last saw active: Sign in
//   again, to reach My Cloud;
// - a saved sign-in this computer could not read and removed: Sign in to
//   OpenMausBot Cloud, claiming no plan (this computer can't tell).
// One message and one action each, no dialog. Not now is kept for each card
// in this computer's onboarding record. Only This computer's own page has the
// Cloud account bridge, so a server open in this window (My Cloud included)
// never shows it, and neither does a browser.
import { useEffect, useState } from "react";
import { Cloud, X } from "lucide-react";
import type { CloudAccountState } from "../../electron/cloud-account.mjs";
import { CLOUD_PLAN_ACTION_LABEL, cloudNoticeKind, cloudPlanAction, cloudPlanStep, cloudPlanView, type CloudNoticeKind } from "@/lib/cloud-plan";
import { t } from "@/lib/i18n";
import { hintSeen, hintSeenPatch } from "@/lib/onboarding";
import type { LocaleKey } from "@/locales";
import { useUpdaterState } from "@/lib/updater";
import { api, useStore } from "@/state/store";

/** Not now on the My Cloud card: in this computer's onboarding record (and
 * this browser's storage, at once), so it does not come back. */
export const CLOUD_NOTICE_DISMISSED = "cloud-notice-my-cloud-dismissed";
/** Not now on either sign-in card, kept the same way. Settings → OpenMausBot
 * Cloud still shows the state and its one action. */
export const CLOUD_NOTICE_SIGN_IN_DISMISSED = "cloud-notice-sign-in-dismissed";

const CARD: Record<CloudNoticeKind, { title: LocaleKey; hint: LocaleKey; dismissed: string }> = {
  "my-cloud": { title: "cloudNotice.myCloud.title", hint: "cloudNotice.myCloud.hint", dismissed: CLOUD_NOTICE_DISMISSED },
  "sign-in-again": { title: "cloudNotice.signIn.title", hint: "cloudNotice.signIn.hint", dismissed: CLOUD_NOTICE_SIGN_IN_DISMISSED },
  removed: { title: "cloudNotice.removed.title", hint: "cloudNotice.removed.hint", dismissed: CLOUD_NOTICE_SIGN_IN_DISMISSED },
};

function storedDismissal(id: string): boolean {
  try { return localStorage.getItem(id) === "1"; } catch { return false; }
}

export function CloudNotice({ quiet = false }: { quiet?: boolean }) {
  const { state, dispatch } = useStore();
  const bridge = window.ogb?.remoteClient?.active ? undefined : window.ogb?.cloudAccount;
  const [account, setAccount] = useState<CloudAccountState | null>(null);
  // Dismissed here, before the record answers (or where it can't be saved).
  const [dismissed, setDismissed] = useState<string[]>(() => [CLOUD_NOTICE_DISMISSED, CLOUD_NOTICE_SIGN_IN_DISMISSED].filter(storedDismissal));
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
  const view = cloudPlanView(account);
  const kind = cloudNoticeKind(view, account);
  const action = cloudPlanAction(view, account);
  const record = state.config?.onboarding;
  if (!kind || !action || action === "sign-out" || quiet || !state.connected || !state.config
    || state.welcomeOpen || state.tourOpen || state.appSettingsOpen
    || (updater && !["idle", "checking"].includes(updater.status))) return null;
  const card = CARD[kind];
  if (dismissed.includes(card.dismissed) || hintSeen(record, card.dismissed)) return null;

  const notNow = () => {
    setDismissed(current => [...current, card.dismissed]);
    try { localStorage.setItem(card.dismissed, "1"); } catch { /* the record below is the durable one */ }
    const patch = hintSeenPatch(record, card.dismissed);
    if (patch) void api("/api/config", { method: "PUT", body: JSON.stringify(patch) })
      .then(config => dispatch({ type: "configStatus", config })).catch(() => {});
  };
  const act = () => {
    if (busy) return;
    setFailed(false); setBusy(true);
    void cloudPlanStep(action, bridge).then(next => setAccount(next), () => setFailed(true)).finally(() => setBusy(false));
  };
  return <aside aria-labelledby="cloud-notice-title" data-cloud-notice={kind} onKeyDown={event => {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); notNow(); }
  }} className="fixed bottom-4 left-4 z-40 max-h-[calc(100dvh-32px)] w-[300px] max-w-[calc(100vw-32px)] overflow-y-auto rounded-xl border border-hairline/40 bg-panel p-3.5 text-ink shadow-2xl shadow-black/20">
    <div className="flex items-start justify-between gap-3">
      <div className="flex items-center gap-2.5">
        <Cloud size={16} className="shrink-0 text-ink-secondary" aria-hidden="true" />
        <h2 id="cloud-notice-title" className="text-[13.5px] font-semibold">{t(card.title)}</h2>
      </div>
      <button type="button" onClick={notNow} aria-label={t("cloudNotice.notNow")} className="ui-icon-button"><X size={16} /></button>
    </div>
    <p className="mt-1 text-[12.5px] leading-relaxed text-ink-secondary">{t(card.hint)}</p>
    <button type="button" disabled={busy} className="ui-button mt-3" onClick={act}>{t(CLOUD_PLAN_ACTION_LABEL[action])}</button>
    {failed && <p role="alert" className="mt-2 text-[12px] text-danger">{t(kind === "my-cloud" ? "cloudNotice.openFailed" : "cloudAccount.actionFailed")}</p>}
  </aside>;
}
