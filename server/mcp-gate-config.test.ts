import { describe, expect, it } from "vitest";

import { gateServer, mcpStdioServer } from "./mcp-gate-config.ts";

const server = { command: "example-mcp", args: ["--stdio"], env: { TOKEN: "disposable-test-token" } };
const input = { name: "notes", server, threadId: "disposable-thread", budget: 0 };
const remote = { type: "http" as const, url: "https://mcp.example.test/mcp", headers: { Authorization: "Bearer disposable-remote-token" } };

describe("MCP scope configuration", () => {
  it("preserves the legacy budget-zero escape hatch only when selection is absent", () => {
    expect(gateServer(input)).toBeNull();
    const gated = gateServer({ ...input, toolScope: { allow: [] } });
    expect(gated).not.toBeNull();
    expect(JSON.parse(gated!.env.OMB_GATE_TOOL_SCOPE)).toEqual({ allow: [] });
    expect(gated!.env.OMB_GATE_BUDGET).toBe("0");
    expect(JSON.parse(gated!.env.OMB_GATE_UPSTREAM)).toEqual(server);
    expect(gated!.args.join(" ")).not.toContain("disposable-test-token");
  });

  it("rejects malformed scopes and unmountable scoped servers instead of returning a bypass", () => {
    expect(() => gateServer({ ...input, toolScope: { allow: null } as never })).toThrow(/tool selection/i);
    expect(() => gateServer({ ...input, server: {}, toolScope: { allow: [] } })).toThrow(/MCP server/i);
  });
});

describe("tool directory configuration", () => {
  it("asks the remote proxy for a directory only when the caller does", () => {
    expect(mcpStdioServer(remote)!.env).not.toHaveProperty("OMB_REMOTE_MCP_DIRECTORY");
    const scope = { allow: ["mcp:whop:*"], deny: ["mcp:whop:payments_create"] };
    const searched = mcpStdioServer(remote, { directory: { name: "whop", toolScope: scope } })!;
    expect(searched.args).toHaveLength(1);
    expect(searched.args![0]).toContain("mcp-remote-proxy");
    expect(JSON.parse(searched.env!.OMB_REMOTE_MCP_DIRECTORY)).toEqual({ name: "whop", toolScope: scope });
    expect(JSON.parse(mcpStdioServer(remote, { directory: { name: "whop" } })!.env!.OMB_REMOTE_MCP_DIRECTORY)).toEqual({ name: "whop" });
    // a command server is mounted as it is
    expect(mcpStdioServer(server, { directory: { name: "notes" } })).toBe(server);
  });

  it("keeps a shared environment's settings in one private record", () => {
    const record = `OMB_REMOTE_MCP_CONFIG_${"0".repeat(64)}`;
    const proxy = mcpStdioServer(remote, { nodeEnv: { ELECTRON_RUN_AS_NODE: "1" }, directory: { name: "whop" }, configEnvName: record })!;
    expect(proxy.args!.slice(1)).toEqual(["--config-env", record]);
    expect(Object.keys(proxy.env!).sort()).toEqual(["ELECTRON_RUN_AS_NODE", record]);
    const settings = JSON.parse(proxy.env![record]);
    expect(JSON.parse(settings.OMB_REMOTE_MCP_SERVER)).toEqual(remote);
    expect(JSON.parse(settings.OMB_REMOTE_MCP_DIRECTORY)).toEqual({ name: "whop" });
    expect(proxy.args!.join(" ")).not.toContain("disposable-remote-token");
  });

  it("hands the proxy the network settings its engine might strip, and makes fetch use them", () => {
    const sourceEnv = { HTTPS_PROXY: "http://proxy.example.test:3128", no_proxy: "localhost", NODE_EXTRA_CA_CERTS: "/etc/corp-ca.pem",
      SSL_CERT_FILE: "/etc/ssl/cert.pem", PATH: "/usr/bin", UNRELATED_SECRET: "not-for-the-proxy" };
    const proxy = mcpStdioServer(remote, { sourceEnv })!;
    expect(proxy.env).toMatchObject({ HTTPS_PROXY: "http://proxy.example.test:3128", no_proxy: "localhost", NODE_EXTRA_CA_CERTS: "/etc/corp-ca.pem", SSL_CERT_FILE: "/etc/ssl/cert.pem", NODE_USE_ENV_PROXY: "1" });
    expect(proxy.env).not.toHaveProperty("UNRELATED_SECRET");
    expect(proxy.env).not.toHaveProperty("PATH");
    // certificates alone need no proxy switch
    expect(mcpStdioServer(remote, { sourceEnv: { NODE_EXTRA_CA_CERTS: "/etc/corp-ca.pem" } })!.env).toEqual({ NODE_EXTRA_CA_CERTS: "/etc/corp-ca.pem", OMB_REMOTE_MCP_SERVER: JSON.stringify(remote) });
    // beside a private record they stay plain names, which a shared environment can pass on
    const record = `OMB_REMOTE_MCP_CONFIG_${"0".repeat(64)}`;
    expect(Object.keys(mcpStdioServer(remote, { sourceEnv, configEnvName: record })!.env!).sort()).toEqual(
      ["HTTPS_PROXY", "NODE_EXTRA_CA_CERTS", "NODE_USE_ENV_PROXY", "SSL_CERT_FILE", "no_proxy", record].sort());
    // and a gated proxy carries them in its upstream descriptor
    const gated = gateServer({ name: "whop", server: remote, threadId: "disposable-thread", budget: 0, toolScope: { allow: [] }, sourceEnv })!;
    expect(JSON.parse(gated.env.OMB_GATE_UPSTREAM).env).toMatchObject({ HTTPS_PROXY: "http://proxy.example.test:3128", NODE_USE_ENV_PROXY: "1" });
  });

  it("refuses settings the proxy could not read", () => {
    expect(() => mcpStdioServer(remote, { configEnvName: "OMB_REMOTE_MCP_SERVER" })).toThrow(/private MCP proxy/);
    expect(() => mcpStdioServer(remote, { directory: { name: "Not A Name" } })).toThrow(/tool search/);
    expect(() => mcpStdioServer(remote, { directory: { name: "whop", toolScope: { allow: null } as never } })).toThrow(/tool search/);
  });

  it("puts a scoped directory inside the gate, which looks through call_tool", () => {
    const scope = { allow: ["mcp:whop:payments_list"] };
    const gated = gateServer({ name: "whop", server: remote, threadId: "disposable-thread", budget: 0, toolScope: scope, directory: true })!;
    expect(gated.args[0]).toContain("mcp-gate");
    expect(gated.env.OMB_GATE_DIRECTORY).toBe("1");
    const upstream = JSON.parse(gated.env.OMB_GATE_UPSTREAM);
    expect(upstream.args[0]).toContain("mcp-remote-proxy");
    expect(JSON.parse(upstream.env.OMB_REMOTE_MCP_DIRECTORY)).toEqual({ name: "whop", toolScope: scope });
    // engines that search tools themselves keep the plain proxy
    const plain = gateServer({ name: "whop", server: remote, threadId: "disposable-thread", budget: 0, toolScope: scope })!;
    expect(plain.env).not.toHaveProperty("OMB_GATE_DIRECTORY");
    expect(JSON.parse(plain.env.OMB_GATE_UPSTREAM).env).not.toHaveProperty("OMB_REMOTE_MCP_DIRECTORY");
    // a command server has no directory to look through
    expect(gateServer({ ...input, toolScope: { allow: [] }, directory: true })!.env).not.toHaveProperty("OMB_GATE_DIRECTORY");
    // an unselected URL server is not gated at all; its caller mounts the proxy
    expect(gateServer({ name: "whop", server: remote, threadId: "disposable-thread", budget: 8_000, directory: true })).toBeNull();
  });
});
