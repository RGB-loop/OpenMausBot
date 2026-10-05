// The Boat or self-hosted VPS choice, in both its homes (the Computer panel
// and a bot's Access settings). My Cloud's cloud computers are the plan's,
// so there it is not offered at all.
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

const fixture = vi.hoisted(() => ({ config: null as unknown }));
vi.mock("@/state/store", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/state/store")>();
  return { ...original, useStore: () => ({ state: { ...original.initialState, config: fixture.config }, dispatch: vi.fn() }) };
});

const { CloudBackendPicker } = await import("./CloudBackendPicker");
const picker = () => renderToStaticMarkup(createElement(CloudBackendPicker, { value: "box", vpsSupported: true, onChange: () => {} }));

describe("CloudBackendPicker", () => {
  afterEach(() => { fixture.config = null; });

  it("offers Boat or a self-hosted VPS on a desktop or self-hosted server", () => {
    fixture.config = { cloudHome: false };
    expect(picker()).toContain("Self-hosted VPS");
  });

  it("is hidden on My Cloud", () => {
    fixture.config = { cloudHome: true };
    expect(picker()).toBe("");
  });
});
