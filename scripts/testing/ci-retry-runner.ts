// This repo's vitest runner: vitest's own, plus CI-only retries for the known
// flaky tests in ci-retry-list.json. A listed test that needed a retry is
// written to OMB_VITEST_RETRY_LOG (one JSON line each), which the vitest job's
// next step puts in the job summary, so a flake that passed stays visible.
// Nothing else changes: unlisted tests, and every run outside CI, get no retry.
import { appendFileSync } from "node:fs";
import type { RunnerTask, RunnerTestCase } from "vitest";
import { VitestTestRunner } from "vitest/runners";
import { CI_RETRIES, findRetryEntry, loadRetryList, retriesInCi, testIdentity } from "./ci-retry-list.mjs";

const list = retriesInCi() ? loadRetryList() : [];

export default class CiRetryRunner extends VitestTestRunner {
  override onBeforeRunTask(test: RunnerTestCase) {
    if (list.length > 0 && findRetryEntry(list, testIdentity(this.config.root, test))) test.retry = CI_RETRIES;
    return super.onBeforeRunTask(test);
  }

  override onAfterRunTask(test: RunnerTask) {
    super.onAfterRunTask(test);
    const retries = test.result?.retryCount ?? 0;
    const log = process.env.OMB_VITEST_RETRY_LOG;
    if (retries === 0 || !log || test.type !== "test") return;
    const line = { ...testIdentity(this.config.root, test), retries, state: test.result?.state, platform: process.platform };
    try {
      appendFileSync(log, `${JSON.stringify(line)}\n`);
    } catch (error) {
      console.warn(`ci-retry-runner: could not record a retry in ${log}: ${String(error)}`);
    }
  }
}
