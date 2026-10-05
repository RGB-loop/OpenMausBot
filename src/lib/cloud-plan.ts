// What the app says about the person's OpenMausBot Cloud plan, in one place:
// Settings → OpenMausBot Cloud (its one next action, cloudPlanAction), the
// Pro card in Settings, the Pro introduction and This computer's My Cloud card
// (cloudNoticeKind) all read it, so no two of them can disagree. The rule the
// owner set: after buying, nothing unexpected or contradictory, never an
// offer to buy to someone who pays (or may pay: an unknown state is not
// "free"), and every state has one message and one next step.
import type { CloudAccountState } from "../../electron/cloud-account.mjs";
import { t } from "@/lib/i18n";

const PLAN_LABEL: Record<string, string> = { personal: "Personal", pro: "Pro", max: "Max" };
/** A paid plan's product name. No tier is an Admin that sells only Pro; a
 * tier newer than this app reads "Cloud". */
export function cloudPlanLabel(tier?: string): string {
  return tier === undefined ? "Pro" : Object.hasOwn(PLAN_LABEL, tier) ? PLAN_LABEL[tier] : "Cloud";
}

export type CloudPlanView =
  /** Not known yet: no snapshot, or a saved sign-in still being read. */
  | { kind: "unknown" }
  | { kind: "signed-out" }
  /** This computer's saved sign-in could not be read, so it was removed. The
   * person may well pay: signing in again is the step, never an offer. */
  | { kind: "removed" }
  | { kind: "connecting" }
  /** Verified: signed in, no plan, no Cloud, no payment being linked. */
  | { kind: "free" }
  /** Verified and active. `checking`: the last checks failed; this is the last verified answer. */
  | { kind: "paid"; label: string; checking: boolean }
  /** A plan that is not active (a payment problem, or it ended) or a Cloud still there without one. */
  | { kind: "attention"; label: string | null }
  /** A payment OMB Cloud received and is linking to this account. */
  | { kind: "purchase"; label: string | null; paidAt?: number }
  /** OMB Cloud cannot be asked right now. `label`: the plan last verified. */
  | { kind: "unverified"; label: string | null }
  /** This computer's sign-in ended. The plan is unaffected. */
  | { kind: "reauth"; label: string | null; reason: "expired" | "access-ended" };

export function cloudPlanView(account: CloudAccountState | null | undefined): CloudPlanView {
  if (!account || (account.status === "signed-out" && account.message === "restoring")) return { kind: "unknown" };
  const last = account.lastPlan ? cloudPlanLabel(account.lastPlan.tier) : null;
  if (account.status === "signed-out") return account.message === "restore-removed" ? { kind: "removed" } : { kind: "signed-out" };
  if (account.status === "connecting") return { kind: "connecting" };
  if (account.status === "reauth-required") return { kind: "reauth", label: last, reason: account.message === "expired" ? "expired" : "access-ended" };
  if (account.status !== "connected") return { kind: "unverified", label: last };
  const entitlement = account.entitlement;
  if (entitlement?.plan === "pro" && entitlement.status === "active") return { kind: "paid", label: cloudPlanLabel(entitlement.tier), checking: account.checking === true };
  if (account.purchase) {
    return { kind: "purchase", label: account.purchase.tier ? cloudPlanLabel(account.purchase.tier) : null, ...(account.purchase.paidAt ? { paidAt: account.purchase.paidAt } : {}) };
  }
  // A lapsed plan, or a Cloud still there (a payment problem, stopped): never "free", never a new purchase.
  if (entitlement?.plan === "pro") return { kind: "attention", label: cloudPlanLabel(entitlement.tier) };
  if (account.machine) return { kind: "attention", label: null };
  return { kind: "free" };
}

/** Only these two may see an offer to buy: nobody who pays, may pay, or
 * whose state is unknown. Signed out, the offer leads with signing in. */
export function buyOfferAllowed(view: CloudPlanView): boolean {
  return view.kind === "signed-out" || view.kind === "free";
}

/** The plan in one line, or null where there is no plan to name. */
export function cloudPlanLine(view: CloudPlanView): string | null {
  switch (view.kind) {
    case "paid": return t("cloudAccount.pro", { plan: view.label });
    case "attention": return t("cloudAccount.inactive", { plan: view.label ?? "OMB Cloud" });
    case "purchase": return t("cloudAccount.purchaseReceived", { plan: view.label ?? "OMB Cloud" });
    case "unverified": return view.label ? t("cloudAccount.lastPlan", { plan: view.label }) : null;
    case "reauth": return view.label ? t("cloudAccount.planName", { plan: view.label }) : null;
    case "free": return t("cloudAccount.free");
    default: return null;
  }
}

/** The one next action Settings → OpenMausBot Cloud offers in each state, or
 * null where there is nothing to press (still loading, or a saved sign-in the
 * app reads again by itself). No Refresh: the app checks with OpenMausBot
 * Cloud by itself, every minute. Sign out (and Cancel, while signing in) stay
 * as quiet links: ways out, not next steps. */
export type CloudPlanAction = "sign-in" | "sign-in-again" | "reopen" | "choose-plan" | "manage" | "update-payment" | "plan-page" | "sign-out";
export function cloudPlanAction(view: CloudPlanView, account: CloudAccountState | null | undefined): CloudPlanAction | null {
  if (!account) return null;
  // Clearing the saved sign-in failed: signing out again is the retry its message names.
  if (account.message === "signout-storage-failed") return "sign-out";
  // A saved sign-in that may only be locked (a keychain) is read again by itself.
  if (account.message === "restore-failed") return null;
  switch (view.kind) {
    case "signed-out": case "removed": return "sign-in";
    case "connecting": return "reopen";
    case "reauth": return "sign-in-again";
    case "free": return "choose-plan";
    case "paid": return "manage";
    case "unverified": return view.label ? "manage" : "plan-page";
    case "attention": return account.machine?.status === "payment-problem" ? "update-payment" : "plan-page";
    case "purchase": return "plan-page";
    default: return null;
  }
}

/** The one card This computer's window shows about My Cloud, bottom left, or
 * null: a paid plan whose My Cloud is ready says the always-on bots are
 * there; a sign-in that ended, or one this computer could not read and
 * removed, asks to sign in again. An offer to buy is the Pro introduction's,
 * shown only where buyOfferAllowed; My Cloud still being set up is shown in
 * Settings. */
export function cloudNoticeKind(view: CloudPlanView, account: CloudAccountState | null | undefined): "my-cloud" | "sign-in-again" | null {
  if (view.kind === "paid" && account?.machine?.status === "ready") return "my-cloud";
  if (view.kind === "removed" || view.kind === "reauth") return "sign-in-again";
  return null;
}
