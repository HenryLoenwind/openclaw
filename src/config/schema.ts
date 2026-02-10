import { z } from "zod";
import { CHANNEL_IDS } from "../channels/registry.js";
import { createSubsystemLogger } from "../logging/subsystem.js";
import { VERSION } from "../version.js";
import { FIELD_HELP } from "./schema.help.ts";
import { FIELD_LABELS } from "./schema.labels.ts";
import { FIELD_PLACEHOLDERS } from "./schema.placeholders.ts";
import { OpenClawSchema } from "./zod-schema.js";
import { sensitive } from "./zod-schema.sensitive.js";

const log = createSubsystemLogger("schema");

export type ConfigUiHint = {
  label?: string;
  help?: string;
  group?: string;
  order?: number;
  advanced?: boolean;
  sensitive?: boolean;
  placeholder?: string;
  itemTemplate?: unknown;
};

export type ConfigUiHints = Record<string, ConfigUiHint>;

export type ConfigSchema = ReturnType<typeof OpenClawSchema.toJSONSchema>;

type JsonSchemaNode = Record<string, unknown>;

export type ConfigSchemaResponse = {
  schema: ConfigSchema;
  uiHints: ConfigUiHints;
  version: string;
  generatedAt: string;
};

export type PluginUiMetadata = {
  id: string;
  name?: string;
  description?: string;
  configUiHints?: Record<
    string,
    Pick<ConfigUiHint, "label" | "help" | "advanced" | "sensitive" | "placeholder">
  >;
  configSchema?: JsonSchemaNode;
};

export type ChannelUiMetadata = {
  id: string;
  label?: string;
  description?: string;
  configSchema?: JsonSchemaNode;
  configUiHints?: Record<string, ConfigUiHint>;
};

const GROUP_LABELS: Record<string, string> = {
  wizard: "Wizard",
  update: "Update",
  diagnostics: "Diagnostics",
  logging: "Logging",
  gateway: "Gateway",
  nodeHost: "Node Host",
  agents: "Agents",
  tools: "Tools",
  bindings: "Bindings",
  audio: "Audio",
  models: "Models",
  messages: "Messages",
  commands: "Commands",
  session: "Session",
  cron: "Cron",
  hooks: "Hooks",
  ui: "UI",
  browser: "Browser",
  talk: "Talk",
  channels: "Messaging Channels",
  skills: "Skills",
  plugins: "Plugins",
  discovery: "Discovery",
  presence: "Presence",
  voicewake: "Voice Wake",
};

const GROUP_ORDER: Record<string, number> = {
  wizard: 20,
  update: 25,
  diagnostics: 27,
  gateway: 30,
  nodeHost: 35,
  agents: 40,
  tools: 50,
  bindings: 55,
  audio: 60,
  models: 70,
  messages: 80,
  commands: 85,
  session: 90,
  cron: 100,
  hooks: 110,
  ui: 120,
  browser: 130,
  talk: 140,
  channels: 150,
  skills: 200,
  plugins: 205,
  discovery: 210,
  presence: 220,
  voicewake: 230,
  logging: 900,
};

const SENSITIVE_PATTERNS = [/token(?!s)/i, /password/i, /secret/i, /api.?key/i];

export function isSensitivePath(path: string): boolean {
  return SENSITIVE_PATTERNS.some((pattern) => pattern.test(path));
}

type JsonSchemaObject = JsonSchemaNode & {
  type?: string | string[];
  properties?: Record<string, JsonSchemaObject>;
  required?: string[];
  additionalProperties?: JsonSchemaObject | boolean;
};

function cloneSchema<T>(value: T): T {
  if (typeof structuredClone === "function") {
    return structuredClone(value);
  }
  return JSON.parse(JSON.stringify(value)) as T;
}

function asSchemaObject(value: unknown): JsonSchemaObject | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  return value as JsonSchemaObject;
}

function isObjectSchema(schema: JsonSchemaObject): boolean {
  const type = schema.type;
  if (type === "object") {
    return true;
  }
  if (Array.isArray(type) && type.includes("object")) {
    return true;
  }
  return Boolean(schema.properties || schema.additionalProperties);
}

function mergeObjectSchema(base: JsonSchemaObject, extension: JsonSchemaObject): JsonSchemaObject {
  const mergedRequired = new Set<string>([...(base.required ?? []), ...(extension.required ?? [])]);
  const merged: JsonSchemaObject = {
    ...base,
    ...extension,
    properties: {
      ...base.properties,
      ...extension.properties,
    },
  };
  if (mergedRequired.size > 0) {
    merged.required = Array.from(mergedRequired);
  }
  const additional = extension.additionalProperties ?? base.additionalProperties;
  if (additional !== undefined) {
    merged.additionalProperties = additional;
  }
  return merged;
}

function buildBaseHints(): ConfigUiHints {
  const hints: ConfigUiHints = {};
  for (const [group, label] of Object.entries(GROUP_LABELS)) {
    hints[group] = {
      label,
      group: label,
      order: GROUP_ORDER[group],
    };
  }
  for (const [path, label] of Object.entries(FIELD_LABELS)) {
    const current = hints[path];
    hints[path] = current ? { ...current, label } : { label };
  }
  for (const [path, help] of Object.entries(FIELD_HELP)) {
    const current = hints[path];
    hints[path] = current ? { ...current, help } : { help };
  }
  for (const [path, placeholder] of Object.entries(FIELD_PLACEHOLDERS)) {
    const current = hints[path];
    hints[path] = current ? { ...current, placeholder } : { placeholder };
  }
  return hints;
}

function applySensitiveHints(hints: ConfigUiHints): ConfigUiHints {
  const next = { ...hints };
  for (const key of Object.keys(next)) {
    if (isSensitivePath(key)) {
      next[key] = { ...next[key], sensitive: true };
    }
  }
  return next;
}

function applyPluginHints(hints: ConfigUiHints, plugins: PluginUiMetadata[]): ConfigUiHints {
  const next: ConfigUiHints = { ...hints };
  for (const plugin of plugins) {
    const id = plugin.id.trim();
    if (!id) {
      continue;
    }
    const name = (plugin.name ?? id).trim() || id;
    const basePath = `plugins.entries.${id}`;

    next[basePath] = {
      ...next[basePath],
      label: name,
      help: plugin.description
        ? `${plugin.description} (plugin: ${id})`
        : `Plugin entry for ${id}.`,
    };
    next[`${basePath}.enabled`] = {
      ...next[`${basePath}.enabled`],
      label: `Enable ${name}`,
    };
    next[`${basePath}.config`] = {
      ...next[`${basePath}.config`],
      label: `${name} Config`,
      help: `Plugin-defined config payload for ${id}.`,
    };

    const uiHints = plugin.configUiHints ?? {};
    for (const [relPathRaw, hint] of Object.entries(uiHints)) {
      const relPath = relPathRaw.trim().replace(/^\./, "");
      if (!relPath) {
        continue;
      }
      const key = `${basePath}.config.${relPath}`;
      next[key] = {
        ...next[key],
        ...hint,
      };
    }
  }
  return next;
}

function applyChannelHints(hints: ConfigUiHints, channels: ChannelUiMetadata[]): ConfigUiHints {
  const next: ConfigUiHints = { ...hints };
  for (const channel of channels) {
    const id = channel.id.trim();
    if (!id) {
      continue;
    }
    const basePath = `channels.${id}`;
    const current = next[basePath] ?? {};
    const label = channel.label?.trim();
    const help = channel.description?.trim();
    next[basePath] = {
      ...current,
      ...(label ? { label } : {}),
      ...(help ? { help } : {}),
    };

    const uiHints = channel.configUiHints ?? {};
    for (const [relPathRaw, hint] of Object.entries(uiHints)) {
      const relPath = relPathRaw.trim().replace(/^\./, "");
      if (!relPath) {
        continue;
      }
      const key = `${basePath}.${relPath}`;
      next[key] = {
        ...next[key],
        ...hint,
      };
    }
  }
  return next;
}

function listHeartbeatTargetChannels(channels: ChannelUiMetadata[]): string[] {
  const seen = new Set<string>();
  const ordered: string[] = [];
  for (const id of CHANNEL_IDS) {
    const normalized = id.trim().toLowerCase();
    if (!normalized || seen.has(normalized)) {
      continue;
    }
    seen.add(normalized);
    ordered.push(normalized);
  }
  for (const channel of channels) {
    const normalized = channel.id.trim().toLowerCase();
    if (!normalized || seen.has(normalized)) {
      continue;
    }
    seen.add(normalized);
    ordered.push(normalized);
  }
  return ordered;
}

function applyHeartbeatTargetHints(
  hints: ConfigUiHints,
  channels: ChannelUiMetadata[],
): ConfigUiHints {
  const next: ConfigUiHints = { ...hints };
  const channelList = listHeartbeatTargetChannels(channels);
  const channelHelp = channelList.length ? ` Known channels: ${channelList.join(", ")}.` : "";
  const help = `Delivery target ("last", "none", or a channel id).${channelHelp}`;
  const paths = ["agents.defaults.heartbeat.target", "agents.list.*.heartbeat.target"];
  for (const path of paths) {
    const current = next[path] ?? {};
    next[path] = {
      ...current,
      help: current.help ?? help,
      placeholder: current.placeholder ?? "last",
    };
  }
  return next;
}

function applyPluginSchemas(schema: ConfigSchema, plugins: PluginUiMetadata[]): ConfigSchema {
  const next = cloneSchema(schema);
  const root = asSchemaObject(next);
  const pluginsNode = asSchemaObject(root?.properties?.plugins);
  const entriesNode = asSchemaObject(pluginsNode?.properties?.entries);
  if (!entriesNode) {
    return next;
  }

  const entryBase = asSchemaObject(entriesNode.additionalProperties);
  const entryProperties = entriesNode.properties ?? {};
  entriesNode.properties = entryProperties;

  for (const plugin of plugins) {
    if (!plugin.configSchema) {
      continue;
    }
    const entrySchema = entryBase
      ? cloneSchema(entryBase)
      : ({ type: "object" } as JsonSchemaObject);
    const entryObject = asSchemaObject(entrySchema) ?? ({ type: "object" } as JsonSchemaObject);
    const baseConfigSchema = asSchemaObject(entryObject.properties?.config);
    const pluginSchema = asSchemaObject(plugin.configSchema);
    const nextConfigSchema =
      baseConfigSchema &&
      pluginSchema &&
      isObjectSchema(baseConfigSchema) &&
      isObjectSchema(pluginSchema)
        ? mergeObjectSchema(baseConfigSchema, pluginSchema)
        : cloneSchema(plugin.configSchema);

    entryObject.properties = {
      ...entryObject.properties,
      config: nextConfigSchema,
    };
    entryProperties[plugin.id] = entryObject;
  }

  return next;
}

function applyChannelSchemas(schema: ConfigSchema, channels: ChannelUiMetadata[]): ConfigSchema {
  const next = cloneSchema(schema);
  const root = asSchemaObject(next);
  const channelsNode = asSchemaObject(root?.properties?.channels);
  if (!channelsNode) {
    return next;
  }
  const channelProps = channelsNode.properties ?? {};
  channelsNode.properties = channelProps;

  for (const channel of channels) {
    if (!channel.configSchema) {
      continue;
    }
    const existing = asSchemaObject(channelProps[channel.id]);
    const incoming = asSchemaObject(channel.configSchema);
    if (existing && incoming && isObjectSchema(existing) && isObjectSchema(incoming)) {
      channelProps[channel.id] = mergeObjectSchema(existing, incoming);
    } else {
      channelProps[channel.id] = cloneSchema(channel.configSchema);
    }
  }

  return next;
}

let cachedBase: ConfigSchemaResponse | null = null;

function stripChannelSchema(schema: ConfigSchema): ConfigSchema {
  const next = cloneSchema(schema);
  const root = asSchemaObject(next);
  if (!root || !root.properties) {
    return next;
  }
  const channelsNode = asSchemaObject(root.properties.channels);
  if (channelsNode) {
    channelsNode.properties = {};
    channelsNode.required = [];
    channelsNode.additionalProperties = true;
  }
  return next;
}

function mapSensitivePaths(schema: z.ZodType, path: string, hints: ConfigUiHints): ConfigUiHints {
  let next = { ...hints };
  let currentSchema: unknown = schema;
  let isSensitive = sensitive.has(schema);

  while (
    currentSchema instanceof z.ZodOptional ||
    currentSchema instanceof z.ZodNullable ||
    currentSchema instanceof z.ZodDefault
  ) {
    isSensitive ||= sensitive.has(currentSchema.unwrap());
    currentSchema = currentSchema.unwrap();
  }

  if (isSensitive) {
    next[path] = { ...next[path], sensitive: true };
  } else if (
    isSensitivePath(path) &&
    !next[path]?.sensitive &&
    !path.endsWith("axTokens") &&
    !path.endsWith("File") &&
    !path.endsWith("TokenReadOnly")
  ) {
    log.warn(`possibly sensitive key found: (${path})`);
  }

  if (currentSchema instanceof z.ZodObject) {
    const shape = currentSchema.shape;
    for (const key in shape) {
      const nextPath = path ? `${path}.${key}` : key;
      next = mapSensitivePaths(shape[key], nextPath, next);
    }
  } else if (currentSchema instanceof z.ZodArray) {
    const nextPath = path ? `${path}[]` : "[]";
    next = mapSensitivePaths(currentSchema.element as z.ZodType, nextPath, next);
  } else if (currentSchema instanceof z.ZodRecord) {
    const nextPath = path ? `${path}.*` : "*";
    next = mapSensitivePaths(currentSchema._def.valueType as z.ZodType, nextPath, next);
  } else if (
    currentSchema instanceof z.ZodUnion ||
    currentSchema instanceof z.ZodDiscriminatedUnion
  ) {
    for (const option of currentSchema.options) {
      next = mapSensitivePaths(option as z.ZodType, path, next);
    }
  } else if (currentSchema instanceof z.ZodIntersection) {
    next = mapSensitivePaths(currentSchema._def.left as z.ZodType, path, next);
    next = mapSensitivePaths(currentSchema._def.right as z.ZodType, path, next);
  }

  return next;
}

/** @internal */
export const __test__ = {
  mapSensitivePaths,
};

function buildBaseConfigSchema(): ConfigSchemaResponse {
  if (cachedBase) {
    return cachedBase;
  }
  const schema = OpenClawSchema.toJSONSchema({
    target: "draft-07",
    unrepresentable: "any",
  });
  schema.title = "OpenClawConfig";
  const hints = mapSensitivePaths(OpenClawSchema, "", buildBaseHints());
  const next = {
    schema: stripChannelSchema(schema),
    uiHints: hints,
    version: VERSION,
    generatedAt: new Date().toISOString(),
  };
  cachedBase = next;
  return next;
}

export function buildConfigSchema(params?: {
  plugins?: PluginUiMetadata[];
  channels?: ChannelUiMetadata[];
}): ConfigSchemaResponse {
  const base = buildBaseConfigSchema();
  const plugins = params?.plugins ?? [];
  const channels = params?.channels ?? [];
  if (plugins.length === 0 && channels.length === 0) {
    return base;
  }
  const mergedHints = applySensitiveHints(
    applyHeartbeatTargetHints(
      applyChannelHints(applyPluginHints(base.uiHints, plugins), channels),
      channels,
    ),
  );
  const mergedSchema = applyChannelSchemas(applyPluginSchemas(base.schema, plugins), channels);
  return {
    ...base,
    schema: mergedSchema,
    uiHints: mergedHints,
  };
}
