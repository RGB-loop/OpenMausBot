// Checking a call's arguments against an MCP tool's input schema, the one
// way every checker does it: the chat-completions runtime before it runs a
// tool, the cloud computer's tools, and the remote proxy's call_tool.
import { Ajv, type ErrorObject, type ValidateFunction } from "ajv";
import { Ajv2020 } from "ajv/dist/2020.js";
import formats from "ajv-formats";

// A schema is a contract, so conversion that drops constraints is not safe.
// Ajv validates the same schema sent to the provider without coercing values,
// applying defaults, removing fields, or fetching external references.
const validatorOptions = {
  strict: true, allErrors: false, coerceTypes: false, useDefaults: false,
  removeAdditional: false, validateFormats: true, ownProperties: true, logger: false as const,
  // These are style diagnostics, not unsupported validation keywords. Valid
  // schemas may require undeclared names, use untyped composition branches,
  // or describe an open tuple. Ajv still enforces every constraint.
  strictRequired: false, strictTypes: false, strictTuples: false,
};

/** A validator for one tool's input schema. `allErrors` collects every
 * problem rather than stopping at the first, for a caller that lists them. */
export function compileToolSchema(schema: Record<string, unknown>, options: { allErrors?: boolean } = {}): ValidateFunction {
  const dialect = schema.$schema;
  if (dialect !== undefined && dialect !== "http://json-schema.org/draft-07/schema#" && dialect !== "https://json-schema.org/draft/2020-12/schema") {
    throw new Error("MCP tool schema uses an unsupported dialect; use JSON Schema draft-07 or 2020-12");
  }
  // One compiler per schema also prevents external IDs from resolving against
  // unrelated tools or retaining schemas after the turn has closed.
  const settings = { ...validatorOptions, allErrors: options.allErrors === true };
  const compiler = dialect === "https://json-schema.org/draft/2020-12/schema" ? new Ajv2020(settings) : new Ajv(settings);
  // ajv-formats is CommonJS and exports the plugin as both module.exports
  // and .default; the latter also matches its NodeNext declaration.
  formats.default(compiler);
  compiler.addFormat("uint32", { type: "number", validate: value => Number.isInteger(value) && value >= 0 && value <= 4294967295 });
  compiler.addFormat("uint64", { type: "number", validate: value => Number.isSafeInteger(value) && value >= 0 });
  try { return compiler.compile(schema); }
  catch { throw new Error("MCP tool schema could not be validated; check its constraints, formats, and references"); }
}

/** A validator's complaints as short lines a model can act on:
 * `company_id: must be string`, `must have required property 'id'`. */
export function schemaProblems(errors: readonly ErrorObject[] | null | undefined, max = 8): string[] {
  const lines = (errors ?? []).map((error) => {
    const where = error.instancePath ? `${error.instancePath.slice(1).replace(/\//g, ".")}: ` : "";
    const params = error.params as Record<string, unknown>;
    const detail = error.keyword === "additionalProperties" ? ` (${String(params.additionalProperty)})`
      : error.keyword === "enum" ? `: ${JSON.stringify(params.allowedValues).slice(0, 200)}` : "";
    return `${where}${error.message ?? "is not valid"}${detail}`;
  });
  return [...new Set(lines)].slice(0, max);
}
