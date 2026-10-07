// A big MCP catalog, searched instead of listed.
//
// Claude Code and Codex on its own login defer MCP tools natively (tool
// search), so a large catalog costs them almost nothing until a tool is
// needed. The other engines put every definition into every model call: the
// chat-completions runtime refuses more than 128 tools, Pi registers each one,
// and Codex on a ChatGPT plan runs with tool_search off. Whop's official
// server lists 425 tools in 1.2 MB of JSON, roughly 315k tokens.
//
// For those engines the remote proxy (mcp-remote-proxy.ts) stands in for a
// big catalog with three tools of its own:
//   search_tools   ranked matches, each with a bounded input signature
//   describe_tool  one tool's exact description and input schema
//   call_tool      runs one tool by name; its result comes back unchanged
// call_tool reaches every tool of the catalog at any time, so a tool the
// model has found never needs activating and cannot drop out of reach again.
//
// Ranking is Okapi BM25 over each tool's name, its area (the name's first
// word) and its description, with light English stemming. Input schemas are
// the server's own untrusted text and are never ranked on: one tool could
// otherwise stuff its field descriptions to win every search. BM25 sits
// behind ToolRanker so a decision model can rank later without the proxy
// changing.
//
// Pure: no I/O. The remote proxy runs the directory; the gate and the drivers
// use directoryCallTarget to see which tool a call really runs, for tool
// selections and approval cards.

type Json = Record<string, unknown>;

/** One upstream tool definition, as the server sent it. */
export interface CatalogTool {
  name: string;
  [key: string]: unknown;
}

/** What a directory tool answers: an ordinary MCP tool result. */
export interface DirectoryResult {
  content: Array<{ type: "text"; text: string }>;
  isError?: true;
}

export const SEARCH_TOOL = "search_tools";
export const DESCRIBE_TOOL = "describe_tool";
export const CALL_TOOL = "call_tool";
const DIRECTORY_TOOLS = new Set([SEARCH_TOOL, DESCRIBE_TOOL, CALL_TOOL]);

/** A catalog with more tools than this is searched. Past a few dozen tools
 * models pick the wrong one more often, and the chat runtime's 128-tool cap
 * is shared by every server of a turn plus the built-ins. */
export const LISTED_TOOLS_MAX = 40;
/** A catalog whose definitions are longer than this is searched too: about
 * 25k tokens, near where Claude Code turns on its own tool search (MCP tools
 * past a tenth of a 200k context), so every engine draws the line in about
 * the same place. A few tools with huge schemas cross it on their own. */
export const LISTED_CHARS_MAX = 100_000;
export const SEARCH_LIMIT_DEFAULT = 8;
export const SEARCH_LIMIT_MAX = 20;
/** One match's description: one line. */
const DESCRIPTION_CHARS = 200;
/** One match's input signature, enough to call most tools without describe. */
export const SIGNATURE_CHARS = 300;
/** The areas listed in search_tools' own description. */
const AREAS_CHARS = 2_000;
/** The upstream instructions quoted in search_tools' own description. */
const ABOUT_CHARS = 400;
/** The upstream initialize instructions a searched server passes through. */
export const INSTRUCTIONS_CHARS = 2_000;
const QUERY_CHARS = 1_000;

function isRecord(value: unknown): value is Json {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

/** Whether `name` is one of the three tools the directory answers itself. */
export function isDirectoryTool(name: unknown): boolean {
  return typeof name === "string" && DIRECTORY_TOOLS.has(name);
}

/** Which upstream tool one call on a searched server runs, for tool scopes
 * and approval cards: the tool itself, call_tool's target, `null` when
 * call_tool names none, or undefined for search_tools and describe_tool,
 * which only read the catalog. */
export function directoryCallTarget(name: string, args: unknown): string | null | undefined {
  if (name === SEARCH_TOOL || name === DESCRIBE_TOOL) return undefined;
  if (name !== CALL_TOOL) return name;
  const target = isRecord(args) ? args.name : undefined;
  return typeof target === "string" && target.trim() ? target : null;
}

/** Whether this catalog is searched rather than listed. A tool that shares a
 * directory tool's name forces the search too, so on a proxy that offers
 * the directory those three names never mean anything else. */
export function searchesCatalog(tools: readonly CatalogTool[]): boolean {
  if (tools.length > LISTED_TOOLS_MAX || tools.some((tool) => isDirectoryTool(tool.name))) return true;
  let chars = 0;
  for (const tool of tools) {
    chars += JSON.stringify(tool).length + 1;
    if (chars > LISTED_CHARS_MAX) return true;
  }
  return false;
}

/** Cut on a character boundary, never inside a surrogate pair. */
function cut(text: string, chars: number): string {
  const kept = text.slice(0, Math.max(0, chars));
  const last = kept.charCodeAt(kept.length - 1);
  return last >= 0xd800 && last <= 0xdbff ? kept.slice(0, -1) : kept;
}

/** At most `maxChars`, with "…" when cut. */
export function bounded(text: string, maxChars: number): string {
  return text.length <= maxChars ? text : `${cut(text, maxChars - 1).trimEnd()}…`;
}

/** Whitespace collapsed to single spaces and bounded, with "…" when cut. */
export function oneLine(text: unknown, maxChars: number): string {
  return typeof text === "string" ? bounded(text.replace(/\s+/g, " ").trim(), maxChars) : "";
}

// ── ranking ─────────────────────────────────────────────────────────────

const STOP_WORDS = new Set([
  "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "its", "me", "my",
  "of", "on", "or", "our", "that", "the", "this", "to", "us", "we", "with", "you", "your",
]);

/** Light English stemming, applied to queries and tools alike: plurals,
 * -ing and -ed, and a final e, so "payments", "listing" and "updated" meet
 * "payment", "list" and "update". Consistency matters more than linguistics:
 * "create" and "creating" both become "creat". */
export function stem(word: string): string {
  let w = word;
  if (w.length > 4 && w.endsWith("ies")) w = `${w.slice(0, -3)}y`;
  else if (w.length > 4 && w.endsWith("sses")) w = w.slice(0, -2);
  else if (w.length > 4 && /(?:[sxz]|ch|sh)es$/.test(w)) w = w.slice(0, -2);
  else if (w.length > 3 && w.endsWith("s") && !/(?:ss|us|is)$/.test(w)) w = w.slice(0, -1);
  if (w.length > 5 && w.endsWith("ing")) w = w.slice(0, -3);
  else if (w.length > 4 && w.endsWith("ed")) w = w.slice(0, -2);
  if (w.length > 3 && w.endsWith("e")) w = w.slice(0, -1);
  return w;
}

/** The stemmed words of a name or a sentence: camelCase and snake_case
 * split, lowercased, stop words and single letters dropped. */
export function terms(text: string): string[] {
  return text
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((word) => word.length > 1 && !STOP_WORDS.has(word))
    .map(stem);
}

/** A tool's area: the first word of its name (`payments` for
 * `payments_list`, `get` for `getUser`), lowercased. */
export function toolArea(name: string): string {
  const head = name.split(/[\s_./:-]+/).find(Boolean) ?? name;
  const word = /^[A-Z]?[a-z0-9]+|^[A-Z]+(?![a-z])/.exec(head)?.[0] ?? head;
  return cut(word.toLowerCase(), 40);
}

/** Orders a catalog for one query, best match first, at most `limit` tools.
 * The seam where a decision model can replace BM25: it gets the same tools
 * the directory would search and returns its pick, in order. */
export type ToolRanker = (query: string, tools: readonly CatalogTool[], limit: number) => readonly CatalogTool[] | Promise<readonly CatalogTool[]>;

const K1 = 1.2;
const B = 0.75;
/** A word of the name counts this many times a word of the description. */
const NAME_WEIGHT = 2;

function frequencies(tool: CatalogTool): { counts: Map<string, number>; length: number } {
  const counts = new Map<string, number>();
  let length = 0;
  const add = (text: unknown, weight: number) => {
    if (typeof text !== "string") return;
    for (const term of terms(text)) {
      counts.set(term, (counts.get(term) ?? 0) + weight);
      length += weight;
    }
  };
  add(tool.name, NAME_WEIGHT);
  add(toolArea(tool.name), 1);
  add(tool.title, 1);
  add(tool.description, 1);
  return { counts, length };
}

function byName(a: CatalogTool, b: CatalogTool): number {
  return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
}

/** Okapi BM25 over names, areas and descriptions; never input schemas. An
 * exact tool name always ranks first; equal scores are ordered by name. */
export const bm25Ranker: ToolRanker = (query, tools, limit) => {
  const wanted = [...new Set(terms(query))];
  const exact = query.trim().toLowerCase();
  const docs = tools.map((tool) => ({ tool, ...frequencies(tool) }));
  const average = docs.reduce((sum, doc) => sum + doc.length, 0) / Math.max(1, docs.length) || 1;
  const df = new Map<string, number>();
  for (const doc of docs) for (const term of wanted) if (doc.counts.has(term)) df.set(term, (df.get(term) ?? 0) + 1);
  const scored: Array<{ tool: CatalogTool; score: number }> = [];
  for (const doc of docs) {
    let score = doc.tool.name.toLowerCase() === exact ? 1_000 : 0;
    for (const term of wanted) {
      const tf = doc.counts.get(term);
      if (!tf) continue;
      const n = df.get(term) ?? 0;
      const idf = Math.log(1 + (docs.length - n + 0.5) / (n + 0.5));
      score += (idf * tf * (K1 + 1)) / (tf + K1 * (1 - B + (B * doc.length) / average));
    }
    if (score > 0) scored.push({ tool: doc.tool, score });
  }
  scored.sort((a, b) => b.score - a.score || byName(a.tool, b.tool));
  return scored.slice(0, limit).map((entry) => entry.tool);
};

// ── signatures ──────────────────────────────────────────────────────────

function literal(value: unknown): string {
  const text = JSON.stringify(value) ?? "unknown";
  return text.length <= 30 ? text : `${cut(text, 29)}…`;
}

function union(parts: string[]): string {
  return [...new Set(parts)].join(" | ");
}

/** One schema as a short TypeScript-style type. Objects stay `object`: the
 * signature is a hint, and describe_tool has the exact shape. */
function typeText(schema: unknown, depth: number): string {
  if (!isRecord(schema)) return "unknown";
  if ("const" in schema) return literal(schema.const);
  if (Array.isArray(schema.enum) && schema.enum.length) {
    const values = union(schema.enum.slice(0, 6).map(literal)) + (schema.enum.length > 6 ? " | …" : "");
    if (values.length <= 80) return values;
  }
  const choices = Array.isArray(schema.anyOf) ? schema.anyOf : Array.isArray(schema.oneOf) ? schema.oneOf : undefined;
  if (choices?.length) return union(choices.map((choice) => typeText(choice, depth)));
  if (Array.isArray(schema.type)) return union(schema.type.map((type) => typeText({ ...schema, type }, depth)));
  switch (schema.type) {
    case "string": return "string";
    case "integer":
    case "number": return "number";
    case "boolean": return "boolean";
    case "null": return "null";
    case "object": return "object";
    case "array": {
      const item = depth < 2 ? typeText(schema.items, depth + 1) : "unknown";
      return item.includes(" ") ? `(${item})[]` : `${item}[]`;
    }
  }
  if (isRecord(schema.properties)) return "object";
  if (Array.isArray(schema.allOf) && schema.allOf.length === 1) return typeText(schema.allOf[0], depth);
  return "unknown";
}

/** A tool's input as a TypeScript-style object type, required fields first,
 * at most `maxChars` long: `{ company_id: string; first?: number }`. Fields
 * that do not fit are counted, never cut in half. */
export function inputSignature(schema: unknown, maxChars = SIGNATURE_CHARS): string {
  const properties = isRecord(schema) && isRecord(schema.properties) ? schema.properties : {};
  const required = new Set(isRecord(schema) && Array.isArray(schema.required) ? schema.required.filter((key): key is string => typeof key === "string") : []);
  const keys = Object.keys(properties);
  const fields = [...keys.filter((key) => required.has(key)), ...keys.filter((key) => !required.has(key))].map((key) =>
    `${/^[A-Za-z_$][\w$]*$/.test(key) ? key : JSON.stringify(key)}${required.has(key) ? "" : "?"}: ${typeText(properties[key], 0)}`);
  if (!fields.length) return "{}";
  const whole = `{ ${fields.join("; ")} }`;
  if (whole.length <= maxChars) return whole;
  let body = "";
  let kept = 0;
  for (const field of fields) {
    const next = body ? `${body}; ${field}` : field;
    const left = fields.length - kept - 1;
    if (`{ ${next}${left ? `; … ${left} more` : ""} }`.length > maxChars) break;
    body = next;
    kept += 1;
  }
  if (kept) return `{ ${body}; … ${fields.length - kept} more }`;
  // Not even the first field fits: show as much of it as room allows.
  const tail = fields.length > 1 ? `; … ${fields.length - 1} more }` : " }";
  return `{ ${cut(fields[0], maxChars - 3 - tail.length)}…${tail}`;
}

// ── the directory ───────────────────────────────────────────────────────

/** The areas of a catalog with their tool counts, biggest first:
 * `payments (12), memberships (9), … and 40 more`. */
export function areaSummary(tools: readonly CatalogTool[], maxChars = AREAS_CHARS): string {
  const counts = new Map<string, number>();
  for (const tool of tools) {
    const area = toolArea(tool.name);
    counts.set(area, (counts.get(area) ?? 0) + 1);
  }
  const sorted = [...counts].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  let text = "";
  let shown = 0;
  for (const [area, count] of sorted) {
    const part = `${shown ? ", " : ""}${area} (${count})`;
    const last = shown === sorted.length - 1;
    // leave room to say how many areas are not listed
    if (text.length + part.length + (last ? 0 : 24) > maxChars) break;
    text += part;
    shown += 1;
  }
  return shown < sorted.length ? `${text}${shown ? ", " : ""}… and ${sorted.length - shown} more` : text;
}

function result(payload: unknown, isError = false): DirectoryResult {
  return { content: [{ type: "text", text: JSON.stringify(payload) }], ...(isError ? { isError: true as const } : {}) };
}

function refusal(text: string): DirectoryResult {
  return { content: [{ type: "text", text }], isError: true };
}

const HOW_TO_SEARCH = `Find tool names with ${SEARCH_TOOL}({ "query": "what you want to do" }), then run one with ${CALL_TOOL}({ "name": "...", "arguments": {...} }).`;

/** Who the server is, for the directory tools' own descriptions. */
export interface DirectoryContext {
  /** the name this server is configured under, e.g. "whop" */
  server: string;
  /** its own `serverInfo` title or name, when it gave one */
  title?: string;
  /** its own initialize instructions, when it gave any */
  instructions?: string;
}

/** One searched catalog: the tools the bot may use, and the three tools
 * that stand in for them. Duplicate names keep their first definition. */
export class ToolDirectory {
  readonly tools: readonly CatalogTool[];
  private readonly index = new Map<string, CatalogTool>();
  private readonly ranker: ToolRanker;

  constructor(tools: readonly CatalogTool[], ranker: ToolRanker = bm25Ranker) {
    for (const tool of tools) if (!this.index.has(tool.name)) this.index.set(tool.name, tool);
    this.tools = [...this.index.values()];
    this.ranker = ranker;
  }

  has(name: string): boolean {
    return this.index.has(name);
  }

  /** search_tools, describe_tool and call_tool, described for this server. */
  listed(context: DirectoryContext): Json[] {
    const who = `the "${context.server}" MCP server${context.title && context.title !== context.server ? ` (${oneLine(context.title, 80)})` : ""}`;
    const about = oneLine(context.instructions, ABOUT_CHARS);
    const nameField = { type: "string", description: `The tool's exact name, as ${SEARCH_TOOL} gave it.` };
    return [
      {
        name: SEARCH_TOOL,
        description: [
          `Search the ${this.tools.length} tools of ${who}. There are too many to list, so find the one you need here: say what you want to do in a few words. Each match comes with what it does and its input fields. Run a match with ${CALL_TOOL}.`,
          `Areas (tools in each): ${areaSummary(this.tools)}.`,
          ...(about ? [`About this server: ${about}`] : []),
        ].join("\n"),
        inputSchema: {
          type: "object",
          properties: {
            query: { type: "string", description: "What you want to do, in a few words." },
            limit: { type: "integer", minimum: 1, maximum: SEARCH_LIMIT_MAX, description: `How many matches to return. Default ${SEARCH_LIMIT_DEFAULT}.` },
          },
          required: ["query"],
          additionalProperties: false,
        },
        annotations: { readOnlyHint: true },
      },
      {
        name: DESCRIBE_TOOL,
        description: `Get the exact description and input schema of one tool of ${who}. Use it when a match's input fields are cut short or you need their exact types.`,
        inputSchema: { type: "object", properties: { name: nameField }, required: ["name"], additionalProperties: false },
        annotations: { readOnlyHint: true },
      },
      {
        name: CALL_TOOL,
        description: `Run one tool of ${who} by its exact name, with its input as arguments, and get its result. Find the name with ${SEARCH_TOOL} first; every tool it finds stays available here.`,
        inputSchema: {
          type: "object",
          properties: {
            name: nameField,
            arguments: { type: "object", description: "The tool's input, matching its input fields.", additionalProperties: true },
          },
          required: ["name"],
          additionalProperties: false,
        },
      },
    ];
  }

  /** search_tools: ranked matches with bounded descriptions, signatures and
   * the hints the server's annotations give. */
  async search(args: unknown): Promise<DirectoryResult> {
    const query = isRecord(args) && typeof args.query === "string" ? args.query.trim() : "";
    const rawLimit = isRecord(args) ? args.limit : undefined;
    if (!query || query.length > QUERY_CHARS || (rawLimit !== undefined && (typeof rawLimit !== "number" || !Number.isFinite(rawLimit)))) {
      return refusal(`${SEARCH_TOOL} needs { "query": string, "limit"?: number from 1 to ${SEARCH_LIMIT_MAX} }.`);
    }
    const limit = Math.min(SEARCH_LIMIT_MAX, Math.max(1, Math.floor((rawLimit as number | undefined) ?? SEARCH_LIMIT_DEFAULT)));
    const found = await this.ranker(query, this.tools, limit);
    const matches = found.slice(0, limit).filter((tool) => this.index.get(tool.name) === tool).map((tool) => {
      const annotations = isRecord(tool.annotations) ? tool.annotations : {};
      return {
        name: tool.name,
        description: oneLine(tool.description ?? tool.title, DESCRIPTION_CHARS),
        input: inputSignature(tool.inputSchema),
        ...(annotations.readOnlyHint === true ? { readOnly: true } : annotations.destructiveHint === true ? { destructive: true } : {}),
      };
    });
    return result(matches.length
      ? { matches, next: `Run one with ${CALL_TOOL}({ "name": "...", "arguments": {...} }). ${DESCRIBE_TOOL} gives a tool's exact input schema when its input is cut short.` }
      : { matches, next: `Nothing matched. Try other words, or one of these areas: ${areaSummary(this.tools, 600)}.` });
  }

  /** describe_tool: one tool's exact definition. */
  describe(args: unknown): DirectoryResult {
    const name = isRecord(args) ? args.name : undefined;
    if (typeof name !== "string") return refusal(`${DESCRIBE_TOOL} needs { "name": string }. ${HOW_TO_SEARCH}`);
    const tool = this.index.get(name);
    if (!tool) return refusal(unknownTool(name));
    return result({
      name: tool.name,
      ...(tool.title !== undefined ? { title: tool.title } : {}),
      description: tool.description ?? "",
      inputSchema: tool.inputSchema ?? { type: "object" },
      ...(tool.annotations !== undefined ? { annotations: tool.annotations } : {}),
    });
  }

  /** call_tool: the upstream `tools/call` to send, or the refusal to answer
   * instead when the name is not in this catalog. */
  call(args: unknown): { name: string; arguments: Json } | { refusal: DirectoryResult } {
    const name = isRecord(args) ? args.name : undefined;
    const input = isRecord(args) ? args.arguments : undefined;
    if (typeof name !== "string" || (input !== undefined && !isRecord(input))) {
      return { refusal: refusal(`${CALL_TOOL} needs { "name": string, "arguments"?: object }. ${HOW_TO_SEARCH}`) };
    }
    if (!this.index.has(name)) return { refusal: refusal(unknownTool(name)) };
    return { name, arguments: input ?? {} };
  }
}

function unknownTool(name: string): string {
  return `No tool named ${JSON.stringify(oneLine(name, 100))} on this server. ${HOW_TO_SEARCH}`;
}
