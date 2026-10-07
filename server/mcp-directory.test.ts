import { describe, expect, it } from "vitest";

import {
  CALL_TOOL,
  DESCRIBE_TOOL,
  LISTED_CHARS_MAX,
  LISTED_TOOLS_MAX,
  SEARCH_LIMIT_DEFAULT,
  SEARCH_LIMIT_MAX,
  SEARCH_TOOL,
  SIGNATURE_CHARS,
  ToolDirectory,
  areaSummary,
  bm25Ranker,
  directoryCallTarget,
  inputSignature,
  searchesCatalog,
  stem,
  terms,
  type CatalogTool,
  type DirectoryResult,
} from "./mcp-directory.ts";
import { whopLikeCatalog } from "./testing/whop-like-catalog.ts";

const catalog = whopLikeCatalog(300);
const text = (result: DirectoryResult) => result.content[0].text;
const matches = async (directory: ToolDirectory, args: unknown) =>
  (JSON.parse(text(await directory.search(args))) as { matches: Array<{ name: string; description: string; input: string; readOnly?: true; destructive?: true }> }).matches;
const tool = (name: string, description = "", inputSchema: unknown = { type: "object" }): CatalogTool => ({ name, description, inputSchema });

describe("when a catalog is searched", () => {
  it("lists up to forty small tools and searches one more", () => {
    const tools = Array.from({ length: LISTED_TOOLS_MAX }, (_, index) => tool(`tool_${index}`));
    expect(searchesCatalog(tools)).toBe(false);
    expect(searchesCatalog([...tools, tool("one_more")])).toBe(true);
  });

  it("searches a few tools whose definitions run past the character limit", () => {
    const huge = (name: string) => tool(name, "x".repeat(LISTED_CHARS_MAX / 2));
    expect(searchesCatalog([huge("a"), tool("b")])).toBe(false);
    expect(searchesCatalog([huge("a"), huge("b"), tool("c")])).toBe(true);
  });

  it("searches any catalog that uses a directory tool's name itself", () => {
    expect(searchesCatalog([tool("read"), tool(CALL_TOOL)])).toBe(true);
    expect(searchesCatalog([tool(SEARCH_TOOL)])).toBe(true);
  });

  it("names the upstream tool each call runs", () => {
    expect(directoryCallTarget(SEARCH_TOOL, { query: "x" })).toBeUndefined();
    expect(directoryCallTarget(DESCRIBE_TOOL, { name: "payments_list" })).toBeUndefined();
    expect(directoryCallTarget(CALL_TOOL, { name: "payments_list", arguments: {} })).toBe("payments_list");
    expect(directoryCallTarget(CALL_TOOL, { arguments: {} })).toBeNull();
    expect(directoryCallTarget(CALL_TOOL, { name: " " })).toBeNull();
    expect(directoryCallTarget("payments_list", {})).toBe("payments_list");
  });
});

describe("BM25 ranking", () => {
  it("stems queries and tools the same way", () => {
    expect(stem("payments")).toBe(stem("payment"));
    expect(stem("listing")).toBe(stem("list"));
    expect(stem("updated")).toBe(stem("update"));
    expect(stem("companies")).toBe(stem("company"));
    expect(stem("statuses")).toBe(stem("status"));
    expect(terms("listPayments for the company_id")).toEqual(["list", "payment", "company", "id"]);
  });

  it("ranks payments_list first for \"list payments\" among Whop-like tools", async () => {
    const found = await bm25Ranker("list payments", catalog, 8);
    expect(found[0].name).toBe("payments_list");
    expect(found.map((entry) => entry.name)).toContain("payments_list_refunded");
    // a tool that only mentions both words in passing ranks below them
    expect(found.findIndex((entry) => entry.name === "stats_get")).toBe(-1);
  });

  it("puts an exact tool name first and breaks ties by name", async () => {
    expect((await bm25Ranker("payments_list_refunded", catalog, 3))[0].name).toBe("payments_list_refunded");
    const twins = [tool("zeta_read", "Read the notes"), tool("alpha_read", "Read the notes")];
    expect((await bm25Ranker("notes", twins, 2)).map((entry) => entry.name)).toEqual(["alpha_read", "zeta_read"]);
  });

  it("never ranks on a tool's input schema, which is the server's own text", async () => {
    const stuffed = tool("widgets_get", "Get one widget.", {
      type: "object", properties: { id: { type: "string", description: "list payments ".repeat(200) } },
    });
    expect(await bm25Ranker("list payments", [stuffed], 8)).toEqual([]);
  });
});

describe("input signatures", () => {
  it("writes required fields first, optional ones with ?, in TypeScript style", () => {
    expect(inputSignature({
      type: "object",
      properties: {
        first: { type: "integer" },
        company_id: { type: "string" },
        order: { type: "string", enum: ["created_at", "updated_at"] },
        tags: { type: "array", items: { type: "string" } },
        status: { anyOf: [{ type: "string" }, { type: "null" }] },
        "x-header": { type: ["string", "number"] },
        filters: { type: "object", properties: { q: { type: "string" } } },
      },
      required: ["company_id"],
    })).toBe('{ company_id: string; first?: number; order?: "created_at" | "updated_at"; tags?: string[]; status?: string | null; "x-header"?: string | number; filters?: object }');
    expect(inputSignature({ type: "object" })).toBe("{}");
    expect(inputSignature(undefined)).toBe("{}");
  });

  it("stays within its bound and counts the fields it leaves out", () => {
    const properties = Object.fromEntries(Array.from({ length: 40 }, (_, index) => [`field_number_${index}`, { type: "string" }]));
    const signature = inputSignature({ type: "object", properties, required: ["field_number_39"] });
    expect(signature.length).toBeLessThanOrEqual(SIGNATURE_CHARS);
    expect(signature.startsWith("{ field_number_39: string; field_number_0?: string")).toBe(true);
    expect(signature).toMatch(/; … \d+ more }$/);
    const lone = inputSignature({ type: "object", properties: { [`k${"y".repeat(400)}`]: { type: "string" } } });
    expect(lone.length).toBeLessThanOrEqual(SIGNATURE_CHARS);
    expect(lone.endsWith("… }")).toBe(true);
  });
});

describe("the directory's three tools", () => {
  const directory = new ToolDirectory(catalog);

  it("describes the server and its areas, bounded", () => {
    const listed = directory.listed({ server: "whop", title: "Whop", instructions: "Run a Whop business.\nPayments, memberships and more." });
    expect(listed.map((entry) => entry.name)).toEqual([SEARCH_TOOL, DESCRIBE_TOOL, CALL_TOOL]);
    const description = listed[0].description as string;
    expect(description).toContain('300 tools of the "whop" MCP server (Whop)');
    expect(description).toContain("payments (8)");
    expect(description).toContain("About this server: Run a Whop business. Payments, memberships and more.");
    expect(description.length).toBeLessThan(3_000);
    const many = Array.from({ length: 500 }, (_, index) => tool(`area${index}_x`));
    const areas = areaSummary(many);
    expect(areas.length).toBeLessThanOrEqual(2_000);
    expect(areas).toMatch(/… and \d+ more$/);
  });

  it("returns bounded one-line matches with signatures and hints", async () => {
    const found = await matches(directory, { query: "list payments" });
    expect(found).toHaveLength(SEARCH_LIMIT_DEFAULT);
    expect(found[0]).toEqual({
      name: "payments_list",
      description: "List payments for a company, newest first. Supports pagination with first and after.",
      input: '{ company_id: string; first?: number; after?: string; order?: "created_at" | "updated_at" }',
      readOnly: true,
    });
    expect((await matches(directory, { query: "delete payments" }))[0]).toMatchObject({ name: "payments_delete", destructive: true });
    // every tool of the area, then the one that only mentions payments
    const payments = (await matches(directory, { query: "payments", limit: 500 })).map((entry) => entry.name);
    expect(payments).toHaveLength(9);
    expect(payments.at(-1)).toBe("stats_get");
    expect(await matches(directory, { query: "company", limit: 500 })).toHaveLength(SEARCH_LIMIT_MAX);
    expect(await matches(directory, { query: "company", limit: 0 })).toHaveLength(1);
    const long = new ToolDirectory([tool("long_one", `${"word ".repeat(100)}\n\nmore`)]);
    const [only] = await matches(long, { query: "long" });
    expect(only.description.length).toBeLessThanOrEqual(200);
    expect(only.description).not.toContain("\n");
  });

  it("says how to go on when nothing matches, and refuses a missing query", async () => {
    const empty = JSON.parse(text(await directory.search({ query: "zzz" })));
    expect(empty).toMatchObject({ matches: [] });
    expect(empty.next).toContain("payments (8)");
    for (const args of [{}, { query: "" }, { query: "x", limit: "8" }, undefined]) {
      expect(await directory.search(args)).toMatchObject({ isError: true });
    }
  });

  it("describes one tool exactly", () => {
    const original = catalog.find((entry) => entry.name === "payments_list")!;
    expect(JSON.parse(text(directory.describe({ name: "payments_list" })))).toEqual({
      name: "payments_list", description: original.description, inputSchema: original.inputSchema, annotations: original.annotations,
    });
    const unknown = directory.describe({ name: "payments_teleport" });
    expect(unknown.isError).toBe(true);
    expect(text(unknown)).toContain(SEARCH_TOOL);
  });

  it("plans call_tool only for a tool in the catalog", () => {
    expect(directory.call({ name: "payments_list", arguments: { company_id: "biz_1" } })).toEqual({ name: "payments_list", arguments: { company_id: "biz_1" } });
    expect(directory.call({ name: "payments_list" })).toEqual({ name: "payments_list", arguments: {} });
    for (const args of [{ name: "payments_teleport" }, { arguments: {} }, { name: "payments_list", arguments: [] }]) {
      const planned = directory.call(args);
      expect("refusal" in planned && planned.refusal.isError).toBe(true);
      expect("refusal" in planned && text(planned.refusal)).toContain(SEARCH_TOOL);
    }
  });

  it("ranks through a replaceable seam, and only ever returns catalog tools", async () => {
    const reversed = new ToolDirectory(catalog, (_query, tools, limit) => [...tools].reverse().slice(0, limit));
    expect((await matches(reversed, { query: "anything", limit: 2 })).map((entry) => entry.name)).toEqual(
      [...catalog].reverse().slice(0, 2).map((entry) => entry.name));
    const forged = new ToolDirectory(catalog, async () => [tool("payments_list", "forged")]);
    expect(await matches(forged, { query: "x" })).toEqual([]);
  });
});
