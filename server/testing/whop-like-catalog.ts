// A synthetic catalog shaped like Whop's official MCP server, which lists 425
// tools in 1.2 MB of JSON: `<area>_<action>` names over a few dozen areas, a
// company_id on nearly every tool, readOnlyHint on the reads, and the odd
// distractor whose description mentions other areas' words. No real Whop
// data: every name and sentence here is made up for tests.
import type { FakeHttpMcpTool } from "./fake-http-mcp-server.ts";

const AREAS = [
  "payments", "memberships", "invoices", "products", "plans", "experiences", "companies", "users",
  "accounts", "reviews", "refunds", "disputes", "transfers", "payouts", "webhooks", "apps",
  "courses", "chats", "forums", "leads", "notifications", "shipments", "entries", "files",
  "messages", "reactions", "taxes", "wallets", "promotions", "affiliates", "authorizations", "checkouts",
  "subscriptions", "customers", "coupons", "orders", "teams", "roles", "audits", "exports",
  "bounties", "licenses", "receipts",
];

const ACTIONS: Array<{ action: string; read: boolean; describe(area: string): string }> = [
  { action: "list", read: true, describe: (area) => `List ${area} for a company, newest first. Supports pagination with first and after.` },
  { action: "get", read: true, describe: (area) => `Retrieve one of the company's ${area} by its ID.` },
  { action: "create", read: false, describe: (area) => `Create new ${area} for a company.` },
  { action: "update", read: false, describe: (area) => `Update fields on existing ${area}.` },
  { action: "delete", read: false, describe: (area) => `Permanently delete ${area}. This cannot be undone.` },
  { action: "search", read: true, describe: (area) => `Find ${area} matching a text query.` },
  { action: "archive", read: false, describe: (area) => `Archive ${area} so they stop appearing in lists.` },
  { action: "export", read: true, describe: (area) => `Export ${area} as a CSV file.` },
  { action: "count", read: true, describe: (area) => `Count ${area} matching filters.` },
  { action: "update-fees", read: false, describe: (area) => `Change the fees charged on ${area}.` },
];

function schemaFor(action: string, padding: number): Record<string, unknown> {
  const properties: Record<string, unknown> = {
    company_id: { type: "string", description: `The company the request is for.${padding ? ` ${"x".repeat(padding)}` : ""}` },
  };
  const required = ["company_id"];
  if (action === "list" || action === "search") {
    properties.first = { type: "integer", minimum: 1, maximum: 100, description: "How many to return." };
    properties.after = { type: "string", description: "Cursor from the previous page." };
    properties.order = { type: "string", enum: ["created_at", "updated_at"] };
  }
  if (action === "search") {
    properties.query = { type: "string" };
    required.push("query");
  }
  if (["get", "update", "delete", "archive", "update-fees"].includes(action)) {
    properties.id = { type: "string" };
    required.push("id");
  }
  if (action === "update" || action === "create") {
    properties.fields = { type: "object", additionalProperties: true };
    properties.tags = { type: "array", items: { type: "string" } };
    properties.status = { anyOf: [{ type: "string" }, { type: "null" }] };
  }
  return { type: "object", properties, required, additionalProperties: false };
}

/** `count` tools, at most 432: two distractors, then each action across
 * every area in turn. With `padding`, each schema carries that many more
 * characters, to reach a catalog of a given size. */
export function whopLikeCatalog(count = 300, padding = 0): FakeHttpMcpTool[] {
  const tools: FakeHttpMcpTool[] = [
    // distractors that mention payments and lists without being the list
    { name: "stats_get", description: "Get statistics for a company, such as payments listed by day.", inputSchema: schemaFor("get", padding), annotations: { readOnlyHint: true } },
    { name: "payments_list_refunded", description: "List payments that were refunded, with their refund details, for a company across every product and plan it sells.", inputSchema: schemaFor("list", padding), annotations: { readOnlyHint: true } },
  ];
  for (const { action, read, describe } of ACTIONS) {
    for (const area of AREAS) {
      if (tools.length >= count) break;
      tools.push({
        name: `${area}_${action}`,
        description: describe(area),
        inputSchema: schemaFor(action, padding),
        annotations: read ? { readOnlyHint: true } : action === "delete" ? { destructiveHint: true } : {},
      });
    }
  }
  return tools.slice(0, count);
}
