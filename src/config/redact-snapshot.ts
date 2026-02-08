import type { ConfigFileSnapshot } from "./types.openclaw.js";
import { isSensitivePath, type ConfigUiHints } from "./schema.js";

/**
 * Sentinel value used to replace sensitive config fields in gateway responses.
 * Write-side handlers (config.set, config.apply, config.patch) detect this
 * sentinel and restore the original value from the on-disk config, so a
 * round-trip through the Web UI does not corrupt credentials.
 */
export const REDACTED_SENTINEL = "__OPENCLAW_REDACTED__";

function buildRedactionLookup(hints: ConfigUiHints): Set<string> {
  let result = new Set<string>();

  for (const [path, hint] of Object.entries(hints)) {
    if (!hint.sensitive) {
      continue;
    }

    const parts = path.split(".");
    let joinedPath = parts.shift() ?? "";
    result.add(joinedPath);

    for (const part of parts) {
      if (part.endsWith("[]")) {
        result.add(`${joinedPath}.${part.slice(0, -2)}`);
      }
      joinedPath = `${joinedPath}.${part}`;
      result.add(joinedPath);
    }
  }
  if (result.size !== 0) {
    result.add("");
  }
  return result;
}

/**
 * Deep-walk an object and replace string values at sensitive paths
 * with the redaction sentinel.
 */
function redactObject(obj: unknown, hints?: ConfigUiHints): unknown {
  if (hints) {
    const lookup = buildRedactionLookup(hints);
    return lookup.has("") ? redactObjectWithLookup(obj, lookup, "", []) : obj;
  } else {
    return redactObjectGuessing(obj, "", []);
  }
}

/**
 * Collect all sensitive string values from a config object.
 * Used for text-based redaction of the raw JSON5 source.
 */
function collectSensitiveValues(obj: unknown, hints?: ConfigUiHints): string[] {
  const result: string[] = [];
  if (hints) {
    const lookup = buildRedactionLookup(hints);
    if (lookup.has("")) {
      redactObjectWithLookup(obj, lookup, "", result);
    }
  } else {
    redactObjectGuessing(obj, "", result);
  }
  return result;
}

/**
 * Worker for redactObject() and collectSensitiveValues().
 * Used when there are ConfigUiHints available.
 */
function redactObjectWithLookup(
  obj: unknown,
  lookup: Set<string>,
  prefix: string,
  values: string[],
): unknown {
  if (obj === null || obj === undefined) {
    return obj;
  }

  if (Array.isArray(obj)) {
    const path = `${prefix}[]`;
    if (!lookup.has(path)) {
      return obj;
    }
    return obj.map((item) => {
      return redactObjectWithLookup(item, lookup, path, values);
    });
  }

  if (typeof obj === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      for (const path of [prefix ? `${prefix}.${key}` : key, prefix ? `${prefix}.*` : "*"]) {
        result[key] = value;
        if (lookup.has(path)) {
          if (typeof value === "string" && !/^\$\{[^}]*\}$/.test(value.trim())) {
            result[key] = REDACTED_SENTINEL;
            values.push(value);
          } else if (typeof value === "object" && value !== null) {
            result[key] = redactObjectWithLookup(value, lookup, path, values);
          }
          break;
        }
      }
    }
    return result;
  }

  return obj;
}

/**
 * Worker for redactObject() and collectSensitiveValues().
 * Used when ConfigUiHints are NOT available.
 */
function redactObjectGuessing(obj: unknown, prefix: string, values: string[]): unknown {
  if (obj === null || obj === undefined) {
    return obj;
  }

  if (Array.isArray(obj)) {
    return obj.map((item) => {
      return redactObjectGuessing(item, `${prefix}[]`, values);
    });
  }

  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
    const dotPath = prefix ? `${prefix}.${key}` : key;
    if (
      isSensitivePath(dotPath) &&
      typeof value === "string" &&
      !/^\$\{[^}]*\}$/.test(value.trim())
    ) {
      result[key] = REDACTED_SENTINEL;
      values.push(value);
    } else if (typeof value === "object" && value !== null) {
      result[key] = redactObjectGuessing(value, dotPath, values);
    } else {
      result[key] = value;
    }
  }
  return result;
}

/**
 * Replace known sensitive values in a raw JSON5 string with the sentinel.
 * Values are replaced longest-first to avoid partial matches.
 */
function redactRawText(raw: string, config: unknown, hints?: ConfigUiHints): string {
  const sensitiveValues = collectSensitiveValues(config, hints);
  sensitiveValues.sort((a, b) => b.length - a.length);
  let result = raw;
  for (const value of sensitiveValues) {
    const escaped = value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    result = result.replace(new RegExp(escaped, "g"), REDACTED_SENTINEL);
  }
  return result;
}

/**
 * Returns a copy of the config snapshot with all sensitive fields
 * replaced by {@link REDACTED_SENTINEL}. The `hash` is preserved
 * (it tracks config identity, not content).
 *
 * Both `config` (the parsed object) and `raw` (the JSON5 source) are scrubbed
 * so no credential can leak through either path.
 *
 * When `uiHints` are provided, sensitivity is determined from the schema hints.
 * Without hints, falls back to regex-based detection via `isSensitivePath()`.
 */
/**
 * Redact sensitive fields from a plain config object (not a full snapshot).
 * Used by write endpoints (config.set, config.patch, config.apply) to avoid
 * leaking credentials in their responses.
 */
export function redactConfigObject<T>(value: T, uiHints?: ConfigUiHints): T {
  return redactObject(value, uiHints) as T;
}

export function redactConfigSnapshot(
  snapshot: ConfigFileSnapshot,
  uiHints?: ConfigUiHints,
): ConfigFileSnapshot {
  const redactedConfig = redactObject(snapshot.config, uiHints) as ConfigFileSnapshot["config"];
  const redactedRaw = snapshot.raw ? redactRawText(snapshot.raw, snapshot.config, uiHints) : null;
  const redactedParsed = snapshot.parsed ? redactObject(snapshot.parsed, uiHints) : snapshot.parsed;

  return {
    ...snapshot,
    config: redactedConfig,
    raw: redactedRaw,
    parsed: redactedParsed,
  };
}

/**
 * Deep-walk `incoming` and replace any {@link REDACTED_SENTINEL} values
 * (on sensitive paths) with the corresponding value from `original`.
 *
 * This is called by config.set / config.apply / config.patch before writing,
 * so that credentials survive a Web UI round-trip unmodified.
 */
export function restoreRedactedValues(
  incoming: unknown,
  original: unknown,
  hints?: ConfigUiHints,
): unknown {
  if (incoming === null || incoming === undefined) {
    return incoming;
  }
  if (typeof incoming !== "object") {
    return incoming;
  }
  if (hints) {
    const lookup = buildRedactionLookup(hints);
    if (lookup.has("")) {
      return restoreRedactedValuesWithLookup(incoming, original, buildRedactionLookup(hints), "");
    } else {
      return incoming;
    }
  } else {
    return restoreRedactedValuesGuessing(incoming, original, "");
  }
}

/**
 * Worker for restoreRedactedValues().
 * Used when there are ConfigUiHints available.
 */
export function restoreRedactedValuesWithLookup(
  incoming: unknown,
  original: unknown,
  lookup: Set<string>,
  prefix: string,
): unknown {
  if (incoming === null || incoming === undefined) {
    return incoming;
  }
  if (typeof incoming !== "object") {
    return incoming;
  }
  if (Array.isArray(incoming)) {
    const path = `${prefix}[]`;
    if (!lookup.has(path)) {
      return incoming;
    }
    const origArr = Array.isArray(original) ? original : [];
    return incoming.map((item, i) =>
      restoreRedactedValuesWithLookup(item, origArr[i], lookup, path),
    );
  }
  const orig =
    original && typeof original === "object" && !Array.isArray(original)
      ? (original as Record<string, unknown>)
      : {};
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(incoming as Record<string, unknown>)) {
    result[key] = value;
    for (const path of [prefix ? `${prefix}.${key}` : key, prefix ? `${prefix}.*` : "*"]) {
      if (lookup.has(path)) {
        if (value === REDACTED_SENTINEL && key in orig) {
          result[key] = orig[key];
        } else if (typeof value === "object" && value !== null) {
          result[key] = restoreRedactedValuesWithLookup(value, orig[key], lookup, path);
        }
        break;
      }
    }
  }
  return result;
}

/**
 * Worker for restoreRedactedValues().
 * Used when ConfigUiHints are NOT available.
 */
export function restoreRedactedValuesGuessing(
  incoming: unknown,
  original: unknown,
  prefix: string,
): unknown {
  if (incoming === null || incoming === undefined) {
    return incoming;
  }
  if (typeof incoming !== "object") {
    return incoming;
  }
  if (Array.isArray(incoming)) {
    const origArr = Array.isArray(original) ? original : [];
    return incoming.map((item, i) =>
      restoreRedactedValuesGuessing(item, origArr[i], `${prefix}[]`),
    );
  }
  const orig =
    original && typeof original === "object" && !Array.isArray(original)
      ? (original as Record<string, unknown>)
      : {};
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(incoming as Record<string, unknown>)) {
    const dotPath = prefix ? `${prefix}.${key}` : key;
    if (isSensitivePath(dotPath) && value === REDACTED_SENTINEL && key in orig) {
      result[key] = orig[key];
    } else if (typeof value === "object" && value !== null) {
      result[key] = restoreRedactedValuesGuessing(value, orig[key], dotPath);
    } else {
      result[key] = value;
    }
  }
  return result;
}
