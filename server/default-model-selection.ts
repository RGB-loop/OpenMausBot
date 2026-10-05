import type { EffortLevel, EngineAccess, ModelCatalog, ModelSelection, ProviderSnapshot } from "./contracts.ts";

interface SelectableInstance {
  instanceId: string;
  driverKind: string;
  snapshot: ProviderSnapshot;
  models: ModelCatalog;
  capabilities?: { effortLevels?: readonly EffortLevel[]; modelVariants?: boolean };
  access?: EngineAccess;
}

/** What an enrolled organisation adds to the choice. Both are omitted (or
 * answer nothing) on a desktop that is not enrolled, which keeps the
 * selection exactly what it has always been. */
export interface DefaultSelectionContext {
  /** True for a Company instance the enrolled organisation provides. */
  company?: (instanceId: string) => boolean;
  /** Why the organisation's desktop policy refuses an instance for bots. */
  refusal?: (instance: SelectableInstance) => string | undefined;
}

const claudeFirst = (instances: readonly SelectableInstance[]) =>
  instances.find((instance) => instance.driverKind === "claudeAgent") ?? instances[0];

/** The ready rule: an engine can run a turn now when it is available, the
 * organisation allows it, and it is not signed out. A custom endpoint brings
 * its own credential, so its sign-in state does not count. */
export function readyToRun(instance: SelectableInstance, context: DefaultSelectionContext = {}): boolean {
  return instance.snapshot.state === "available" && context.refusal?.(instance) === undefined &&
    (instance.access === "custom" || instance.snapshot.authenticated !== false);
}

/** A saved choice is intentional: an unavailable provider or removed model
 * sends new bots to setup instead of silently changing their provider. */
export function selectDefaultModelSelection(
  instances: readonly SelectableInstance[],
  preferred?: ModelSelection,
  context: DefaultSelectionContext = {},
): ModelSelection {
  if (preferred) {
    const instance = instances.find((candidate) => candidate.instanceId === preferred.instanceId);
    if (
      instance?.snapshot.state !== "available" ||
      instance.snapshot.authenticated === false ||
      // An organisation that disallows the saved engine makes it unusable,
      // exactly like an unavailable one: setup, never another provider.
      context.refusal?.(instance) !== undefined ||
      (preferred.variant !== undefined && !instance.capabilities?.modelVariants) ||
      !(instance.models.default === preferred.model || instance.models.options.some((model) => model.id === preferred.model))
    ) {
      return { instanceId: "", model: "" };
    }
    const selection = { ...preferred };
    // A saved effort can outlive driver support. Keep the intentional model,
    // but let the provider use its own effort default instead of failing turn 1.
    if (selection.effort && !instance.capabilities?.effortLevels?.includes(selection.effort)) delete selection.effort;
    return selection;
  }
  // The organisation's policy is inert (no refusal) unless this desktop is enrolled.
  const available = instances.filter((instance) => instance.snapshot.state === "available" && context.refusal?.(instance) === undefined);
  const company = context.company;
  let pick: SelectableInstance | undefined;
  if (company && instances.some((instance) => company(instance.instanceId))) {
    // Enrolled: an installed but signed-out personal Claude would send every
    // new bot to setup although a Company model can run. Prefer instances that
    // can run a turn now; a working personal engine still wins, so billing
    // stays the person's explicit choice.
    const ready = available.filter((instance) => readyToRun(instance, context));
    pick = claudeFirst(ready.filter((instance) => !company(instance.instanceId))) ?? claudeFirst(ready) ?? claudeFirst(available);
  } else {
    pick = claudeFirst(available);
  }
  return { instanceId: pick?.instanceId ?? "", model: pick?.models.default ?? "" };
}

/** Where a bot goes when its engine is removed: the engine a new bot gets,
 * as long as it can run a turn now. That is the workspace's saved default,
 * else the first ready engine (Claude first), else the first available one.
 * Empty only when nothing is available at all. */
export function selectReplacementModelSelection(
  instances: readonly SelectableInstance[],
  preferred?: ModelSelection,
  context: DefaultSelectionContext = {},
): ModelSelection {
  if (preferred) {
    const saved = selectDefaultModelSelection(instances, preferred, context);
    const instance = instances.find((candidate) => candidate.instanceId === saved.instanceId);
    if (instance && readyToRun(instance, context)) return saved;
  }
  const available = instances.filter((instance) => instance.snapshot.state === "available" && context.refusal?.(instance) === undefined);
  const pick = claudeFirst(available.filter((instance) => readyToRun(instance, context))) ?? claudeFirst(available);
  return { instanceId: pick?.instanceId ?? "", model: pick?.models.default ?? "" };
}

/** Complete a new bot's selection with the workspace's new-bot effort. An
 * explicit effort or model variant is the caller's choice and wins; an engine
 * that does not offer the level keeps sending none rather than failing turn 1. */
export function withNewBotEffort(
  selection: ModelSelection,
  effort: EffortLevel | undefined,
  offered: readonly EffortLevel[] | undefined,
): ModelSelection {
  if (!effort || selection.effort !== undefined || selection.variant !== undefined || !offered?.includes(effort)) {
    return selection;
  }
  return { ...selection, effort };
}
