import { expect, it } from "vitest";
import type { CloudAccountState } from "../../electron/cloud-account.mjs";
import { buyOfferAllowed, cloudNoticeKind, cloudPlanAction, cloudPlanView } from "./cloud-plan";

const account = { id: "a", email: "person@example.test" };
const free: CloudAccountState = { status: "connected", account, entitlement: { plan: "free", status: "inactive", expiresAt: null, version: 1 } };
const paid = (extra: Partial<CloudAccountState> = {}): CloudAccountState => ({
  ...free, entitlement: { plan: "pro", tier: "max", status: "active", expiresAt: 1_900_000_000_000, version: 2 }, ...extra,
});
const origin = "https://omb-u-1a2b3c4d5e6f.fly.dev";
const removed: CloudAccountState = { status: "signed-out", message: "restore-removed" };

it("a saved sign-in that could not be read and was removed is its own state: never an offer to buy", () => {
  expect(cloudPlanView(removed)).toEqual({ kind: "removed" });
  expect(buyOfferAllowed(cloudPlanView(removed))).toBe(false);
  // Signed out by choice, or never signed in, may still be offered a plan.
  expect(buyOfferAllowed(cloudPlanView({ status: "signed-out" }))).toBe(true);
  expect(buyOfferAllowed(cloudPlanView({ status: "signed-out", message: "signin-failed" }))).toBe(true);
});

// Settings → OpenMausBot Cloud: one next action per state, never three
// buttons, and nothing that does nothing (Refresh: the app checks by itself).
it("Settings has one next action for every state", () => {
  const action = (state: CloudAccountState | null) => cloudPlanAction(cloudPlanView(state), state);
  const table: Array<[CloudAccountState | null, ReturnType<typeof cloudPlanAction>]> = [
    [null, null],
    [{ status: "signed-out", message: "restoring" }, null],
    [{ status: "signed-out" }, "sign-in"],
    [{ status: "signed-out", message: "enrollment-expired" }, "sign-in"],
    [removed, "sign-in"],
    [{ status: "connecting", enrollment: { userCode: "ABCDE-FGHJK", expiresAt: 1 } }, "reopen"],
    [free, "choose-plan"],
    [paid(), "manage"],
    [paid({ checking: true }), "manage"],
    [paid({ machine: { status: "ready", origin } }), "manage"],
    [{ ...free, machine: { status: "payment-problem", origin } }, "update-payment"],
    [{ ...free, machine: { status: "stopped", origin } }, "plan-page"],
    [{ ...free, entitlement: { plan: "pro", status: "inactive", expiresAt: null, version: 3 } }, "plan-page"],
    [{ ...free, purchase: { state: "confirming", tier: "personal" } }, "plan-page"],
    [{ status: "unavailable", message: "unreachable", lastPlan: { tier: "personal", active: true } }, "manage"],
    [{ status: "unavailable", message: "unreachable" }, "plan-page"],
    [{ status: "reauth-required", message: "expired", lastPlan: { tier: "max", active: true } }, "sign-in-again"],
    [{ status: "reauth-required", message: "access-ended" }, "sign-in-again"],
    // A saved sign-in that may only be locked is read again by itself: nothing to press.
    [{ status: "unavailable", message: "restore-failed" }, null],
    // Clearing it failed: Sign out again is the retry its message names.
    [{ status: "unavailable", message: "signout-storage-failed" }, "sign-out"],
  ];
  for (const [state, expected] of table) expect(action(state), JSON.stringify(state)).toBe(expected);
});

// This computer's window, bottom left: one card about My Cloud, or none.
it("on This computer, a paid plan with My Cloud ready points there; an ended or removed sign-in asks to sign in again", () => {
  const notice = (state: CloudAccountState | null) => cloudNoticeKind(cloudPlanView(state), state);
  expect(notice(paid({ machine: { status: "ready", origin } }))).toBe("my-cloud");
  expect(notice(paid({ machine: { status: "ready", origin }, checking: true }))).toBe("my-cloud");
  expect(notice(removed)).toBe("sign-in-again");
  expect(notice({ status: "reauth-required", message: "expired", lastPlan: { tier: "max", active: true } })).toBe("sign-in-again");
  expect(notice({ status: "reauth-required", message: "access-ended" })).toBe("sign-in-again");
  // My Cloud not ready yet (Settings shows its setup), and every other state: nothing here.
  for (const state of [
    null, { status: "signed-out" }, { status: "signed-out", message: "restoring" }, free, paid(), paid({ machine: { status: "provisioning" } }),
    paid({ machine: { status: "failed", origin } }), { ...free, machine: { status: "stopped", origin } },
    { status: "unavailable", message: "unreachable", lastPlan: { tier: "max", active: true } }, { status: "unavailable", message: "restore-failed" },
    { status: "connecting" },
  ] as Array<CloudAccountState | null>) expect(notice(state), JSON.stringify(state)).toBeNull();
});
