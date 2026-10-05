// The Computer engine is gone. It ran a whole turn on Boat's own agent, which
// has no AI sign-in on a Cloud, so a bot set to it failed there. Every bot now
// keeps its own engine and uses a cloud computer as a tool. This file is the
// one place that still knows the removed engine's names, so settings saved
// before the removal can be moved off it, once, on every kind of server.
import type { InstanceConfigMap } from "./contracts.ts";

/** The removed engine's driver kind ("boxAgent" was Boat's historical name). */
export const REMOVED_COMPUTER_DRIVER = "boxAgent";
/** The instance id every default fleet gave it. */
const REMOVED_COMPUTER_INSTANCE = "computer";

/** Instance ids that named the Computer engine. A saved fleet may hold the
 * default id or one of its own; an id the person reused for another engine
 * is theirs and stays. */
export function removedComputerInstanceIds(saved: InstanceConfigMap | undefined): Set<string> {
  const ids = new Set<string>();
  const reused = saved && Object.hasOwn(saved, REMOVED_COMPUTER_INSTANCE) &&
    saved[REMOVED_COMPUTER_INSTANCE]!.driver !== REMOVED_COMPUTER_DRIVER;
  if (!reused) ids.add(REMOVED_COMPUTER_INSTANCE);
  for (const [id, entry] of Object.entries(saved ?? {})) {
    if (entry.driver === REMOVED_COMPUTER_DRIVER) ids.add(id);
  }
  return ids;
}

/** A fleet without the removed engine: a saved entry for it is dropped, so
 * it is never offered and the next save leaves it off disk. */
export function withoutComputerEngine(map: InstanceConfigMap): InstanceConfigMap {
  for (const [id, entry] of Object.entries(map)) {
    if (entry.driver === REMOVED_COMPUTER_DRIVER) delete map[id];
  }
  return map;
}

/** The line a moved bot's conversation shows, in plain words. */
export function computerEngineMoveText(botName: string, engine: string): string {
  return `${botName} now uses ${engine}, because the Computer engine was removed. Where ${botName} works is unchanged.`;
}
