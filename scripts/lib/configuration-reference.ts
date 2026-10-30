/** Configuration facts come from Spring metadata and the Helm chart, never a host .env file. */
import { readFile } from "node:fs/promises";
import path from "node:path";
import { parse, parseAllDocuments } from "yaml";
import {
	applicationImage,
	CHART,
	readDeployContainers,
	renderDeployTopology,
} from "./deploy-topology.ts";
import { isRecord, parseJson } from "./json.ts";

export const REPO_ROOT = path.resolve(import.meta.dirname, "../..");
export const REFERENCE_PAGE = "docs/admin/configuration-reference.mdx";
const RESOURCES = "server/application/src/main/resources";
export const APPLICATION_FILES = [
	"application.yml",
	"application-prod.yml",
	"application-worker.yml",
	"application-webhook.yml",
].map((file) => `${RESOURCES}/${file}`);
const DEVELOPER_PROFILES = new Set(["local", "e2e", "playwright", "test"]);
export interface Placeholder {
	readonly name: string;
	readonly fallback?: string;
	readonly required: boolean;
}
export function placeholders(text: string): Placeholder[] {
	const found: Placeholder[] = [];
	let index = text.indexOf("${");
	while (index !== -1) {
		const end = closingBrace(text, index + 2);
		if (end === -1) {
			break;
		}
		const body = text.slice(index + 2, end);
		const placeholder = readPlaceholder(body);
		if (placeholder) {
			found.push(placeholder);
			if (placeholder.fallback !== undefined) {
				found.push(...placeholders(placeholder.fallback));
			}
		}
		index = text.indexOf("${", end + 1);
	}
	return found;
}

function closingBrace(text: string, from: number): number {
	let depth = 1;
	for (let index = from; index < text.length; index += 1) {
		if (text.startsWith("${", index)) {
			depth += 1;
			index += 1;
		} else if (text[index] === "}") {
			depth -= 1;
			if (depth === 0) {
				return index;
			}
		}
	}
	return -1;
}

function readPlaceholder(body: string): Placeholder | undefined {
	const name = /^[A-Z][A-Z0-9_]*/u.exec(body)?.[0];
	if (name === undefined) {
		return undefined;
	}
	const rest = body.slice(name.length);
	if (rest === "") {
		return { name, required: false };
	}
	return rest.startsWith(":") ? { name, fallback: rest.slice(1), required: false } : undefined;
}

/** A fallback as an operator reads it: a nested placeholder names its variable, not its own fallback. */
export function displayDefault(fallback: string): string {
	let shown = "";
	let index = 0;
	while (index < fallback.length) {
		const start = fallback.indexOf("${", index);
		if (start === -1) {
			shown += fallback.slice(index);
			break;
		}
		const end = closingBrace(fallback, start + 2);
		if (end === -1) {
			shown += fallback.slice(index);
			break;
		}
		const inner = readPlaceholder(fallback.slice(start + 2, end));
		shown +=
			fallback.slice(index, start) + (inner ? `\${${inner.name}}` : fallback.slice(start, end + 1));
		index = end + 1;
	}
	return shown;
}

function yamlDocuments(text: string, file: string): unknown[] {
	return parseAllDocuments(text).map((document) => {
		if (document.errors.length > 0) {
			throw new Error(`${file}: invalid YAML`);
		}
		return document.toJS() as unknown;
	});
}
/** `spring.config.activate.on-profile` of a document, or undefined for one that always applies. */
function documentProfile(document: unknown): string | undefined {
	if (!isRecord(document) || !isRecord(document.spring) || !isRecord(document.spring.config)) {
		return undefined;
	}
	const { activate } = document.spring.config;
	const profile = isRecord(activate) ? activate["on-profile"] : undefined;
	return typeof profile === "string" ? profile : undefined;
}

/** `server.tomcat.remoteip` + `[mail.smtp.auth]` → `server.tomcat.remoteip[mail.smtp.auth]`. */
function joinKey(parent: string, key: string): string {
	if (parent === "") {
		return key;
	}
	return key.startsWith("[") ? `${parent}${key}` : `${parent}.${key}`;
}

function* scalars(value: unknown, keyPath: string): Generator<readonly [string, string]> {
	if (Array.isArray(value)) {
		for (const [index, item] of value.entries()) {
			yield* scalars(item, `${keyPath}[${index}]`);
		}
	} else if (isRecord(value)) {
		for (const [key, child] of Object.entries(value)) {
			yield* scalars(child, joinKey(keyPath, key));
		}
	} else if (typeof value === "string") {
		yield [keyPath, value];
	}
}

export interface SpringBinding {
	readonly variable: string;
	readonly property: string;
	readonly fallback?: string;
	/** `default` for `application.yml`, otherwise the profile of the file or document. */
	readonly profile: string;
}

/** Every environment variable that an application YAML placeholder binds, outside developer profiles. */
export function readSpringBindings(
	files: readonly (readonly [file: string, text: string])[],
): SpringBinding[] {
	const bindings: SpringBinding[] = [];
	for (const [file, text] of files) {
		const fileProfile =
			/application-(?<profile>[a-z-]+)\.yml$/u.exec(file)?.groups?.profile ?? "default";
		for (const document of yamlDocuments(text, file)) {
			const profile = documentProfile(document) ?? fileProfile;
			if (DEVELOPER_PROFILES.has(profile)) {
				continue;
			}
			bindings.push(
				...[...scalars(document, "")].flatMap(([property, value]) =>
					placeholders(value).map((placeholder) => ({
						variable: placeholder.name,
						property,
						fallback: placeholder.fallback,
						profile,
					})),
				),
			);
		}
	}
	return bindings;
}

export interface MetadataProperty {
	readonly name: string;
	readonly type?: string;
	readonly description?: string;
	readonly defaultValue?: unknown;
}

/** Spring's canonical comparison: case, `-` and `_` do not matter, the dots of the path do. */
const canonical = (name: string): string => name.replaceAll(/[-_]/gu, "").toLowerCase();
/** Relaxed binding from the environment: `_` stands for `.` and `-` alike. */
const relaxed = (name: string): string => name.replaceAll(/[-_.]/gu, "").toLowerCase();

export class Metadata {
	readonly #byCanonical = new Map<string, MetadataProperty>();
	readonly #byRelaxed = new Map<string, MetadataProperty[]>();

	constructor(properties: Iterable<MetadataProperty>) {
		for (const property of properties) {
			const known = this.#byCanonical.get(canonical(property.name));
			if (known?.description !== undefined && known.description !== "") {
				continue;
			}
			this.#byCanonical.set(canonical(property.name), property);
			const key = relaxed(property.name);
			this.#byRelaxed.set(key, [...(this.#byRelaxed.get(key) ?? []), property]);
		}
	}

	/**
	 * The metadata of a YAML key path. An entry of a map or a list (`a.b[x]`, `a.b.key`) has none
	 * of its own; it takes the description of the map or list that holds it.
	 */
	property(keyPath: string): MetadataProperty | undefined {
		const exact = this.#byCanonical.get(canonical(keyPath.replaceAll(/\[\d+\]$/gu, "")));
		if (exact) {
			return exact;
		}
		const stripped = keyPath.replaceAll(/\[[^\]]*\]/gu, "");
		const segments = stripped.split(".");
		for (let length = segments.length - (stripped === keyPath ? 1 : 0); length > 0; length -= 1) {
			const container = this.#byCanonical.get(canonical(segments.slice(0, length).join(".")));
			if (container?.type !== undefined && /^java\.util\.(?:Map|List|Set)/u.test(container.type)) {
				return container;
			}
		}
		return undefined;
	}

	/** The canonical name of a YAML key path when the metadata has it, so `privateKey` reads `private-key`. */
	name(keyPath: string): string {
		const indexed = /(?<entry>\[[^\]]*\])$/u.exec(keyPath)?.groups?.entry ?? "";
		const known = this.#byCanonical.get(
			canonical(keyPath.slice(0, keyPath.length - indexed.length)),
		);
		return known ? `${known.name}${indexed}` : keyPath;
	}

	/** The property a container variable binds through relaxed binding, when exactly one matches. */
	relaxedProperty(variable: string): MetadataProperty | undefined {
		const matches = this.#byRelaxed.get(relaxed(variable)) ?? [];
		return matches.length === 1 ? matches[0] : undefined;
	}
}

const sentence = (text: string): string =>
	text.trim() === "" || /[.!?:]$/u.test(text.trim()) ? text.trim() : `${text.trim()}.`;
const unique = <T>(values: Iterable<T>): T[] => [...new Set(values)];
const MDX_SPECIAL = /[{}<>]/gu;
const PIPE = String.raw`\|`;
const escapeText = (text: string): string =>
	text.replaceAll("|", PIPE).replaceAll(MDX_SPECIAL, (character) => `\\${character}`);

/**
 * Metadata descriptions are Javadoc. Its inline tags become Markdown: `{@code x}` a code span,
 * `{@link Type#member}` the plain name. Only the prose around a code span is escaped for MDX.
 */
function cellText(javadoc: string): string {
	const text = javadoc
		.replaceAll(/\{@link(?:plain)?\s+[^\s}]+\s+(?<label>[^}]+)\}/gu, "$<label>")
		.replaceAll(/\{@link(?:plain)?\s+(?<target>[^}\s]+)\}/gu, (...match: unknown[]) => {
			const groups = match.at(-1);
			const target = isRecord(groups) && typeof groups.target === "string" ? groups.target : "";
			return target.replace(/^#/u, "").replace("#", ".");
		})
		.replaceAll(/\{@literal\s+(?<text>[^}]*)\}/gu, "$<text>")
		.replaceAll("&lt;", "<")
		.replaceAll("&gt;", ">")
		.replaceAll("&amp;", "&");
	const capitalized = text.charAt(0).toUpperCase() + text.slice(1);
	return capitalized
		.split(/(?<code>\{@code\s+[^}]*\})/u)
		.map((part) => {
			const codeSpan = /^\{@code\s+(?<body>[^}]*)\}$/u.exec(part)?.groups?.body;
			return codeSpan === undefined ? escapeText(part) : code(codeSpan.trim());
		})
		.join("");
}
const code = (text: string): string => {
	if (text === "") {
		return "";
	}
	const fence = text.includes("`") ? "`` " : "`";
	return `${fence}${text.replaceAll("|", PIPE)}${fence.trim() === "``" ? " ``" : "`"}`;
};

/** Product names that are written in camel case on purpose. */
const CAMEL_CASE_NAMES = new Set(["gVisor", "pgBackRest", "iOS", "macOS"]);

/**
 * What an operator description must not contain. A default belongs in the Default column, where the
 * YAML placeholder sets it. A Java identifier or class name is a leak of the implementation.
 */
export function descriptionProblems(description: string): string[] {
	const problems: string[] = [];
	if (/\(default\b|\bdefaults? (?:to|is)\b|\bdefault:/iu.test(description)) {
		problems.push("states a default");
	}
	const camel = (description.match(/\b[a-z]+(?:[A-Z][a-z0-9]*)+\b/gu) ?? []).filter(
		(word) => !CAMEL_CASE_NAMES.has(word),
	);
	if (camel.length > 0) {
		problems.push(`names a Java identifier: ${unique(camel).join(", ")}`);
	}
	const types =
		description.match(
			/\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+){2,}\b|\b[a-z]+(?:\.[a-z]+)+\.[A-Z]\w*/gu,
		) ?? [];
	if (types.length > 0) {
		problems.push(`names a Java type: ${unique(types).join(", ")}`);
	}
	return problems;
}

export interface PageRow {
	readonly variable: string;
	readonly description: string;
}

/** The variable and description of every table row on the committed page. */
export function readPage(text: string): PageRow[] {
	const rows: PageRow[] = [];
	for (const line of text.split(/\r?\n/u)) {
		if (!line.startsWith("| `")) {
			continue;
		}
		const cells = line
			.slice(1, -1)
			.split(/(?<!\\)\|/u)
			.map((cell) => cell.trim());
		const variable = /^`(?<name>[^`]+)`$/u.exec(cells[0] ?? "")?.groups?.name;
		if (variable !== undefined) {
			rows.push({
				variable: /^[A-Z][A-Z0-9_]*$/u.test(variable) ? variable : `values.${variable}`,
				description: cells.at(-1) ?? "",
			});
		}
	}
	return rows;
}

export interface ChartRow {
	readonly key: string;
	readonly defaultValue: unknown;
	readonly type: string;
	readonly required: boolean;
	readonly constraint: string;
}

/** Follow schema properties even when the source defaults leave a required map empty. */
export function chartRows(schema: unknown, defaults: unknown): ChartRow[] {
	if (!isRecord(schema) || !isRecord(schema.properties) || !isRecord(defaults)) {
		throw new Error("Malformed chart configuration sources");
	}
	const definitions = isRecord(schema.definitions) ? schema.definitions : {};
	const resolve = (node: unknown): Record<string, unknown> => {
		if (!isRecord(node)) {
			throw new Error("Malformed chart schema node");
		}
		if (typeof node.$ref !== "string") {
			return node;
		}
		const name = node.$ref.replace(/^#\/definitions\//u, "");
		const definition = definitions[name];
		if (!isRecord(definition)) {
			throw new Error(`Unknown chart schema definition ${name}`);
		}
		return { ...definition, ...node };
	};
	const rows: ChartRow[] = [];
	const walk = (
		properties: Record<string, unknown>,
		values: Record<string, unknown>,
		prefix: string,
		required: unknown,
	): void => {
		for (const [name, raw] of Object.entries(properties)) {
			const node = resolve(raw);
			const key = prefix ? `${prefix}.${name}` : name;
			const value = values[name];
			if (isRecord(node.properties)) {
				walk(node.properties, isRecord(value) ? value : {}, key, node.required);
			} else {
				rows.push({
					key,
					defaultValue: value,
					type: typeof node.type === "string" ? node.type : "value",
					required: Array.isArray(required) && required.includes(name),
					constraint:
						typeof node.description === "string"
							? node.description
							: Object.entries(node)
									.filter(([field]) =>
										[
											"enum",
											"minimum",
											"maximum",
											"pattern",
											"minItems",
											"maxItems",
											"additionalProperties",
										].includes(field),
									)
									.map(([field, constraint]) => `${field}: ${JSON.stringify(constraint)}`)
									.join(". "),
				});
			}
		}
	};
	walk(schema.properties, defaults, "", schema.required);
	return rows.toSorted((a, b) => a.key.localeCompare(b.key));
}

export interface Sources {
	readonly bindings: readonly SpringBinding[];
	readonly containers: ReturnType<typeof readDeployContainers>;
	readonly values: readonly ChartRow[];
}
export interface Row {
	readonly variable: string;
	readonly settings: readonly string[];
	readonly defaultValue: string;
	readonly services: readonly string[];
	readonly description: string;
}

export function join(sources: Sources, metadata: Metadata): { rows: Row[]; unbound: string[] } {
	const rows: Row[] = [];
	const unbound: string[] = [];
	const variables = unique(sources.bindings.map((binding) => binding.variable));
	const containers = sources.containers.filter((container) => container.image === applicationImage);
	for (const container of containers) {
		for (const variable of container.environment.keys()) {
			if (
				/^(?:JAVA_TOOL_OPTIONS|JAVA_OPTS|BPL_[A-Z0-9_]+|BPE_[A-Z0-9_]+|THC_[A-Z0-9_]+|POD_IP|XDG_CONFIG_HOME|XDG_CACHE_HOME|TZ)$/u.test(
					variable,
				)
			) {
				continue;
			}
			if (!variables.includes(variable)) {
				if (metadata.relaxedProperty(variable)) {
					variables.push(variable);
				} else {
					unbound.push(`${container.name}: ${variable}`);
				}
			}
		}
	}
	for (const variable of variables.toSorted()) {
		const bindings = sources.bindings.filter((binding) => binding.variable === variable);
		const settings = unique(bindings.map((binding) => metadata.name(binding.property)));
		const property = metadata.relaxedProperty(variable);
		if (settings.length === 0 && property) {
			settings.push(property.name);
		}
		rows.push({
			variable,
			settings,
			defaultValue: unique(
				bindings.map(
					(binding) =>
						`${binding.profile}: ${binding.fallback !== undefined && binding.fallback !== "" ? displayDefault(binding.fallback) : "(empty)"}`,
				),
			).join("; "),
			services: containers
				.filter((container) => container.environment.has(variable))
				.map((container) => container.name),
			description: unique(
				settings
					.map((setting) => sentence(metadata.property(setting)?.description ?? ""))
					.filter(Boolean),
			).join(" "),
		});
	}
	return { rows, unbound };
}

export async function readSources(root = REPO_ROOT): Promise<Sources> {
	const files = await Promise.all(
		APPLICATION_FILES.map(
			async (file) => [file, await readFile(path.join(root, file), "utf8")] as const,
		),
	);
	return {
		bindings: readSpringBindings(files),
		containers: readDeployContainers(renderDeployTopology(root)),
		values: chartRows(
			parseJson(await readFile(path.join(root, CHART, "values.schema.json"), "utf8")),
			parse(await readFile(path.join(root, CHART, "values.yaml"), "utf8")),
		),
	};
}
export function requiredVariables(sources: Sources): Set<string> {
	return new Set([
		...sources.bindings.map((binding) => binding.variable),
		...sources.containers
			.filter((container) => container.image === applicationImage)
			.flatMap((container) =>
				[...container.environment.keys()].filter(
					(variable) =>
						!/^(?:JAVA_TOOL_OPTIONS|JAVA_OPTS|BPL_[A-Z0-9_]+|BPE_[A-Z0-9_]+|THC_[A-Z0-9_]+|POD_IP|XDG_CONFIG_HOME|XDG_CACHE_HOME|TZ)$/u.test(
							variable,
						),
				),
			),
		...sources.values.map((row) => `values.${row.key}`),
	]);
}
export function renderPage(rows: readonly Row[], values: readonly ChartRow[]): string {
	const lines = [
		"---",
		"id: configuration-reference",
		"title: Configuration reference",
		"description: Helm values and application settings for a Kubernetes deployment.",
		"---",
		"",
		"The Helm chart owns the deployment configuration.",
		"This page comes from the chart schema, chart defaults, rendered workloads, application YAML, and Spring configuration metadata.",
		"",
		"Set chart values in your Helm values file.",
		"The Required column reports the schema at each object. Conditional requirements also apply.",
		"See [Kubernetes deployment](./kubernetes) for those requirements, Secret keys, profiles, and operator procedures.",
		"",
		"## Helm values",
		"",
		"| Key | Type | Default | Required in object | Constraints |",
		"| --- | --- | --- | --- | --- |",
	];
	for (const row of values) {
		lines.push(
			`| ${code(row.key)} | ${row.type} | ${code(row.defaultValue === undefined ? "supplied by release or operator" : JSON.stringify(row.defaultValue))} | ${row.required ? "yes" : ""} | ${/^(?:enum|minimum|maximum|pattern|minItems|maxItems|additionalProperties):/u.test(row.constraint) ? code(row.constraint) : escapeText(row.constraint || "—")} |`,
		);
	}
	lines.push(
		"",
		"## Application environment",
		"",
		"The chart sets these variables on the listed workload containers.",
		"Change the corresponding Helm value or Secret key.",
		"A host `.env` file does not configure a Pod.",
		"",
		"Variables without a listed container keep the application default.",
		"Defaults below come from the application YAML. The chart can replace them.",
		"[Configuration readiness](./configuration-readiness) reports incomplete instance configuration.",
		"",
		"| Variable | Setting | Application default | Containers | Description |",
		"| --- | --- | --- | --- | --- |",
	);
	for (const row of rows) {
		lines.push(
			`| ${code(row.variable)} | ${row.settings.map(code).join("<br />")} | ${code(row.defaultValue)} | ${row.services.map(code).join(", ")} | ${cellText(row.description)} |`,
		);
	}
	return `${lines.join("\n")}\n`;
}
