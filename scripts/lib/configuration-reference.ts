/**
 * The operator configuration reference: one row for every variable an operator can set.
 *
 * Two sources describe a variable, and each fact has one of them as its home:
 *
 * - The application YAML files bind an environment variable to a Spring property through a
 *   `${VAR:default}` placeholder. They own the variable's name and its default. Spring's
 *   configuration metadata, generated from `@ConfigurationProperties` Javadoc and
 *   `additional-spring-configuration-metadata.json`, owns the description and the type. A Compose
 *   variable that a container receives under its own name binds through relaxed binding instead.
 * - The Compose files and `.env.example` own the variables that only Compose reads, such as memory
 *   limits and image references. The comment above a key in `.env.example` is its description.
 *
 * `generate-configuration-reference.ts` joins them into `docs/admin/configuration-reference.mdx`.
 * `check-configuration-reference.ts` proves, without a build, that the committed page still has a
 * described row for every variable the sources name.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

import { parseAllDocuments, type Tags } from "yaml";

import { isRecord } from "./json.ts";

export const REPO_ROOT = path.resolve(import.meta.dirname, "..", "..");
export const REFERENCE_PAGE = "docs/admin/configuration-reference.mdx";

const RESOURCES = "server/application/src/main/resources";
/** The profiles a deployment runs. Build-only and test profiles (`specs`, `e2e`, …) set nothing an operator sets. */
export const APPLICATION_FILES = [
	`${RESOURCES}/application.yml`,
	`${RESOURCES}/application-prod.yml`,
	`${RESOURCES}/application-worker.yml`,
	`${RESOURCES}/application-webhook.yml`,
];
/** Documents of these profiles run only on a developer's machine. */
const DEVELOPER_PROFILES = new Set(["local", "e2e", "playwright", "test"]);

/**
 * The supported self-hosted stack: `docker/self-host/compose.yaml` includes the first four files, and
 * the backup, restore and journald files are its optional overlays.
 */
export const COMPOSE_FILES = [
	"docker/compose.proxy.yaml",
	"docker/compose.core.yaml",
	"docker/compose.app.yaml",
	"docker/self-host/compose.single-host.yaml",
	"docker/self-host/compose.backup.yaml",
	"docker/self-host/compose.restore.yaml",
	"docker/observability/compose.journald.yaml",
];
/** The template of the supported stack. The reference deployment's `docker/.env.example` is not documented. */
export const ENV_EXAMPLES = ["docker/self-host/.env.example"];

/** The prefix of a container variable in a Compose use's field. */
const ENVIRONMENT = "environment.";
/** The image that runs the three Java runtime roles. */
const APPLICATION_IMAGE = "HEPHAESTUS_IMAGE_APPLICATION_SERVER";
/**
 * Variables of the Java containers that the JVM or the buildpack launcher reads, not Spring.
 * THC_* configures the buildpack's container health checker.
 */
const RUNTIME_VARIABLES =
	/^(?:JAVA_TOOL_OPTIONS|JAVA_OPTS|BPL_[A-Z0-9_]+|BPE_[A-Z0-9_]+|THC_[A-Z0-9_]+|TZ)$/u;

export interface Placeholder {
	readonly name: string;
	/** The raw fallback after `:`, nested placeholders included. Absent for `${NAME}`. */
	readonly fallback?: string;
	/** Compose `${NAME:?message}` and `${NAME?message}`. */
	readonly required: boolean;
	/**
	 * A Compose `environment:` key with no value. Compose hands the container the value from the
	 * shell or the `--env-file`, and leaves the variable unset when neither has it.
	 */
	readonly passThrough?: boolean;
}

/**
 * Every `${…}` in a string, outer ones before the ones nested in their fallbacks. Spring writes the
 * fallback after `:`, Compose after `:-` or `-`, and marks a required value with `:?` or `?`.
 */
export function placeholders(text: string, syntax: "spring" | "compose"): Placeholder[] {
	const found: Placeholder[] = [];
	let index = text.indexOf("${");
	while (index !== -1) {
		// Compose writes `$${` for a literal `${` that the container's shell expands.
		if (syntax === "compose" && text[index - 1] === "$") {
			index = text.indexOf("${", index + 2);
			continue;
		}
		const end = closingBrace(text, index + 2);
		if (end === -1) {
			break;
		}
		const body = text.slice(index + 2, end);
		const placeholder = readPlaceholder(body, syntax);
		if (placeholder) {
			found.push(placeholder);
			if (placeholder.fallback !== undefined) {
				found.push(...placeholders(placeholder.fallback, syntax));
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

function readPlaceholder(body: string, syntax: "spring" | "compose"): Placeholder | undefined {
	const name = /^[A-Z][A-Z0-9_]*/u.exec(body)?.[0];
	if (name === undefined) {
		return undefined;
	}
	const rest = body.slice(name.length);
	if (rest === "") {
		return { name, required: false };
	}
	if (syntax === "spring") {
		return rest.startsWith(":") ? { name, fallback: rest.slice(1), required: false } : undefined;
	}
	const operator = /^(?<op>:-|-|:\?|\?)/u.exec(rest)?.groups?.op;
	if (operator === undefined) {
		return undefined;
	}
	const value = rest.slice(operator.length);
	return operator.endsWith("?")
		? { name, required: true }
		: { name, fallback: value, required: false };
}

/** A fallback as an operator reads it: a nested placeholder names its variable, not its own fallback. */
export function displayDefault(fallback: string, syntax: "spring" | "compose"): string {
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
		const inner = readPlaceholder(fallback.slice(start + 2, end), syntax);
		shown +=
			fallback.slice(index, start) + (inner ? `\${${inner.name}}` : fallback.slice(start, end + 1));
		index = end + 1;
	}
	return shown;
}

/** A field that a later file replaces (`!override`) or removes (`!reset`) instead of merging into it. */
interface Replaced {
	readonly replaced: true;
	readonly value: unknown;
}

const isReplaced = (value: unknown): value is Replaced =>
	isRecord(value) && value.replaced === true;

const COMPOSE_TAGS: Tags = ["!override", "!reset"].flatMap((tag) => {
	const resolve = (value: unknown): Replaced => ({
		replaced: true,
		value: tag === "!reset" ? undefined : value,
	});
	return [
		{ tag, collection: "map" as const, resolve },
		{ tag, collection: "seq" as const, resolve },
		{ tag, resolve },
	];
});

function yamlDocuments(text: string, file: string, customTags?: Tags): unknown[] {
	return parseAllDocuments(text, { merge: true, customTags }).map((document) => {
		const error = document.errors[0];
		if (error) {
			throw new Error(`${file}: ${error.message}`);
		}
		return document.toJS({ maxAliasCount: -1 }) as unknown;
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
					placeholders(value, "spring").map((placeholder) => ({
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

export interface ComposeUse {
	readonly file: string;
	readonly service: string;
	/** `environment.NAME` for a container variable, otherwise the service field, such as `mem_limit`. */
	readonly field: string;
	/** The whole value the field holds. */
	readonly template: string;
	readonly placeholder: Placeholder;
}

export interface ComposeService {
	readonly name: string;
	readonly java: boolean;
	/** Container variable name to the value template, across every file that declares the service. */
	readonly environment: Map<string, string>;
}

export interface ComposeModel {
	readonly services: Map<string, ComposeService>;
	readonly uses: ComposeUse[];
}

/** `[name, value]` pairs; a value of `undefined` is a pass-through key. */
function environmentEntries(environment: unknown): [string, string | undefined][] {
	if (isRecord(environment)) {
		return Object.entries(environment).flatMap(([key, value]): [string, string | undefined][] => {
			if (value === null) {
				return [[key, undefined]];
			}
			return typeof value === "string" || typeof value === "number" || typeof value === "boolean"
				? [[key, `${value}`]]
				: [];
		});
	}
	if (Array.isArray(environment)) {
		return environment.flatMap((entry: unknown): [string, string | undefined][] => {
			if (typeof entry !== "string") {
				return [];
			}
			const separator = entry.indexOf("=");
			return [
				separator === -1
					? [entry, undefined]
					: [entry.slice(0, separator), entry.slice(separator + 1)],
			];
		});
	}
	return [];
}

function interpolations(file: string, service: string, definition: unknown): ComposeUse[] {
	return [...scalars(definition, "")].flatMap(([field, template]) =>
		placeholders(template, "compose").map((placeholder) => ({
			file,
			service,
			field,
			template,
			placeholder,
		})),
	);
}

/** A replaced field drops what earlier files contributed to it, as Compose does. */
function dropReplaced(
	uses: ComposeUse[],
	services: ReadonlyMap<string, ComposeService>,
	service: string,
	declared: Record<string, unknown>,
): void {
	for (const [field, value] of Object.entries(declared)) {
		if (!isReplaced(value)) {
			continue;
		}
		const fed = (use: ComposeUse) =>
			use.service === service &&
			(use.field === field ||
				use.field.startsWith(`${field}.`) ||
				use.field.startsWith(`${field}[`));
		uses.splice(0, uses.length, ...uses.filter((use) => !fed(use)));
		if (field === "environment") {
			services.get(service)?.environment.clear();
		}
	}
}

/** Services, their container environment, and every interpolation, with Compose's own merges resolved. */
export function readCompose(
	files: readonly (readonly [file: string, text: string])[],
): ComposeModel {
	const services = new Map<string, ComposeService>();
	const uses: ComposeUse[] = [];
	for (const [file, text] of files) {
		for (const document of yamlDocuments(text, file, COMPOSE_TAGS)) {
			if (!isRecord(document) || !isRecord(document.services)) {
				continue;
			}
			// A top-level `configs:` entry is file content that Compose interpolates, such as the broker's.
			const configs = isRecord(document.configs) ? document.configs : {};
			for (const [name, definition] of Object.entries(configs)) {
				uses.push(...interpolations(file, `configs.${name}`, definition));
			}
			for (const [name, declared] of Object.entries(document.services)) {
				if (!isRecord(declared)) {
					continue;
				}
				dropReplaced(uses, services, name, declared);
				const definition = Object.fromEntries(
					Object.entries(declared).map(([field, value]) => [
						field,
						isReplaced(value) ? value.value : value,
					]),
				);
				const known = services.get(name);
				const image = typeof definition.image === "string" ? definition.image : "";
				const environment = environmentEntries(definition.environment);
				// A pass-through key reads its own name, so it behaves as `${NAME}` without a fallback.
				const templates = environment.map(([key, value]): [string, string] => [
					key,
					value ?? `\${${key}}`,
				]);
				services.set(name, {
					name,
					java: (known?.java ?? false) || image.includes(APPLICATION_IMAGE),
					environment: new Map([...(known?.environment ?? []), ...templates]),
				});
				uses.push(
					...interpolations(file, name, {
						...definition,
						environment: Object.fromEntries(templates),
					}).map((use) =>
						environment.some(
							([key, value]) => value === undefined && use.field === `${ENVIRONMENT}${key}`,
						)
							? { ...use, placeholder: { ...use.placeholder, passThrough: true } }
							: use,
					),
				);
			}
		}
	}
	return { services, uses };
}

export interface EnvExampleKey {
	readonly name: string;
	readonly file: string;
	/** The comment block above the key, without section rules. Empty when the key has none. */
	readonly description: string;
}

/**
 * Keys of a `.env.example`, active (`KEY=`) or commented out (`#KEY=`). A comment block describes
 * the keys that follow it, until a blank line or the next comment block.
 */
export function readEnvExample(file: string, text: string): EnvExampleKey[] {
	const keys: EnvExampleKey[] = [];
	let comment: string[] = [];
	let afterKey = false;
	for (const line of text.split(/\r?\n/u)) {
		const key = /^\s*#?\s*(?<name>[A-Z][A-Z0-9_]*)=/u.exec(line)?.groups?.name;
		if (key !== undefined) {
			keys.push({ name: key, file, description: comment.join(" ") });
			afterKey = true;
		} else if (line.trim() === "") {
			comment = [];
			afterKey = false;
		} else if (line.trimStart().startsWith("#")) {
			if (afterKey) {
				comment = [];
				afterKey = false;
			}
			const remark = line.trimStart().replace(/^#\s?/u, "").trim();
			// A section rule (`# --- General ---`) names a group, it does not describe a key.
			if (!/^-{2,}.*-{2,}$/u.test(remark)) {
				comment.push(remark);
			}
		}
	}
	return keys;
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

export type RowKind = "application" | "compose" | "unforwarded";

/** Marks a default that Compose sets as the container value, rather than a fallback. */
const COMPOSE_SETS = "Compose: ";

export interface Row {
	readonly kind: RowKind;
	readonly variable: string;
	/** Spring properties for an application row; the container fields it feeds for a Compose row. */
	readonly settings: readonly string[];
	readonly defaultValue: string;
	readonly required: boolean;
	readonly services: readonly string[];
	readonly description: string;
}

export interface Sources {
	readonly bindings: readonly SpringBinding[];
	readonly compose: ComposeModel;
	readonly envExamples: readonly EnvExampleKey[];
}

export interface Join {
	readonly rows: Row[];
	/** Variables that Compose hands a Java container and that bind no setting: a setting that does nothing. */
	readonly unbound: string[];
	/** `.env.example` keys that no Compose file and no application YAML reads. */
	readonly unread: string[];
}

const unique = <T>(values: Iterable<T>): T[] => [...new Set(values)];
const byName = (a: string, b: string): number => a.localeCompare(b);
const sentence = (text: string): string => {
	const trimmed = text.trim().replaceAll(/\s+/gu, " ");
	return trimmed === "" || /[.!?:]$/u.test(trimmed) ? trimmed : `${trimmed}.`;
};

function groupBy<T>(items: Iterable<T>, key: (item: T) => string): Map<string, T[]> {
	const groups = new Map<string, T[]>();
	for (const item of items) {
		groups.set(key(item), [...(groups.get(key(item)) ?? []), item]);
	}
	return groups;
}

/** The order in which a profile's default wins for what Compose runs: production over the base file. */
const PROFILE_ORDER = ["prod", "default", "worker", "webhook"];
const isMainProfile = (binding: SpringBinding): boolean =>
	binding.profile === "prod" || binding.profile === "default";

function springDefault(bindings: readonly SpringBinding[]): string {
	const ordered = bindings.toSorted(
		(a, b) => PROFILE_ORDER.indexOf(a.profile) - PROFILE_ORDER.indexOf(b.profile),
	);
	const shown = (binding: SpringBinding) =>
		binding.fallback === undefined ? "" : displayDefault(binding.fallback, "spring");
	const main = ordered.find(isMainProfile);
	const mainDefault = main === undefined ? undefined : shown(main);
	const overlays = ordered
		.filter((binding) => !isMainProfile(binding) && shown(binding) !== mainDefault)
		.map((binding) => `${binding.profile}: ${shown(binding) === "" ? "(empty)" : shown(binding)}`);
	return unique([...(mainDefault === undefined ? [] : [mainDefault]), ...overlays]).join("; ");
}

interface JoinContext {
	readonly sources: Sources;
	readonly metadata: Metadata;
	readonly bindings: ReadonlyMap<string, SpringBinding[]>;
	/** The first described occurrence of each `.env.example` key. */
	readonly examples: ReadonlyMap<string, EnvExampleKey>;
}

function describe(context: JoinContext, properties: readonly string[]): string {
	return unique(
		properties
			.map((property) => sentence(context.metadata.property(property)?.description ?? ""))
			.filter((description) => description !== ""),
	).join(" ");
}

/** The properties a Java container variable binds: a placeholder first, relaxed binding second. */
function containerProperties(context: JoinContext, variable: string): string[] {
	const viaPlaceholder = context.bindings.get(variable);
	if (viaPlaceholder) {
		return unique(viaPlaceholder.map((binding) => context.metadata.name(binding.property)));
	}
	const viaRelaxed = context.metadata.relaxedProperty(variable);
	return viaRelaxed ? [viaRelaxed.name] : [];
}

const containerVariable = (use: ComposeUse): string => use.field.slice(ENVIRONMENT.length);

function findUnbound(context: JoinContext): string[] {
	return [...context.sources.compose.services.values()]
		.filter((service) => service.java)
		.flatMap((service) =>
			[...service.environment.keys()]
				.filter(
					(variable) =>
						!RUNTIME_VARIABLES.test(variable) &&
						containerProperties(context, variable).length === 0,
				)
				.map((variable) => `${service.name}: ${variable}`),
		)
		.toSorted(byName);
}

/** Uses that hand the variable to a Java container as it is: the whole value is this one placeholder. */
function directUses(
	context: JoinContext,
	variable: string,
	uses: readonly ComposeUse[],
): ComposeUse[] {
	return uses.filter((use) => {
		const template = use.template.trim();
		return (
			context.sources.compose.services.get(use.service)?.java === true &&
			use.field.startsWith(ENVIRONMENT) &&
			!RUNTIME_VARIABLES.test(containerVariable(use)) &&
			template.startsWith("${") &&
			template.endsWith("}") &&
			placeholders(template, "compose")[0]?.name === variable
		);
	});
}

function operatorRow(
	context: JoinContext,
	variable: string,
	uses: readonly ComposeUse[],
	direct: readonly ComposeUse[],
): Row {
	const properties = unique(
		direct.flatMap((use) => containerProperties(context, containerVariable(use))),
	);
	const fallbacks = unique(
		uses.flatMap((use) =>
			use.placeholder.fallback === undefined
				? []
				: [displayDefault(use.placeholder.fallback, "compose")],
		),
	);
	const shared = {
		variable,
		required: uses.some((use) => use.placeholder.required),
		services: unique(uses.map((use) => use.service)).toSorted(byName),
	};
	if (properties.length === 0) {
		return {
			...shared,
			kind: "compose",
			// A field, not each of its entries: `labels`, not `labels[13]`.
			settings: unique(uses.map((use) => use.field.replace(/\[\d+\]$/u, ""))).toSorted(byName),
			defaultValue: fallbacks.join("; "),
			description: sentence(context.examples.get(variable)?.description ?? ""),
		};
	}
	const springBindings = direct.flatMap(
		(use) => context.bindings.get(containerVariable(use)) ?? [],
	);
	// Without a fallback, `${NAME}` hands the container an empty value, and a pass-through key none.
	const passesThrough =
		direct.length > 0 && direct.every((use) => use.placeholder.passThrough === true);
	let defaultValue = fallbacks.join("; ");
	if (fallbacks.length === 0 && passesThrough) {
		defaultValue = springDefault(springBindings);
	}
	return {
		...shared,
		kind: "application",
		settings: properties,
		defaultValue,
		description: describe(context, properties),
	};
}

function unforwardedRow(
	context: JoinContext,
	variable: string,
	bindings: readonly SpringBinding[],
): Row {
	const properties = unique(bindings.map((binding) => context.metadata.name(binding.property)));
	const composeValues = unique(
		[...context.sources.compose.services.values()].flatMap((service) => {
			const template = service.java ? service.environment.get(variable) : undefined;
			return template === undefined ? [] : [displayDefault(template, "compose")];
		}),
	);
	const setByCompose = composeValues.length > 0;
	return {
		kind: "unforwarded",
		variable,
		settings: properties,
		defaultValue: setByCompose
			? `${COMPOSE_SETS}${composeValues.join("; ")}`
			: springDefault(bindings),
		required:
			!setByCompose &&
			bindings.some((binding) => binding.fallback === undefined && isMainProfile(binding)),
		services: [],
		description: describe(context, properties),
	};
}

export function join(sources: Sources, metadata: Metadata): Join {
	const examples = new Map<string, EnvExampleKey>();
	for (const key of sources.envExamples) {
		if (
			examples.get(key.name)?.description !== undefined &&
			examples.get(key.name)?.description !== ""
		) {
			continue;
		}
		examples.set(key.name, key);
	}
	const context: JoinContext = {
		sources,
		metadata,
		bindings: groupBy(sources.bindings, (binding) => binding.variable),
		examples,
	};
	const usesByVariable = groupBy(sources.compose.uses, (use) => use.placeholder.name);

	const rows: Row[] = [];
	const unread: string[] = [];
	for (const variable of unique([...usesByVariable.keys(), ...examples.keys()]).toSorted(byName)) {
		const uses = usesByVariable.get(variable) ?? [];
		if (uses.length === 0) {
			if (containerProperties(context, variable).length === 0) {
				unread.push(variable);
			}
			continue;
		}
		rows.push(operatorRow(context, variable, uses, directUses(context, variable, uses)));
	}
	// A container variable that Compose fills from a differently named key keeps its own row, so a
	// reader who finds its name in application.yml learns that setting it in `.env` does nothing.
	for (const [variable, bindings] of [...context.bindings].toSorted(([a], [b]) => byName(a, b))) {
		if (!usesByVariable.has(variable)) {
			rows.push(unforwardedRow(context, variable, bindings));
		}
	}
	return { rows, unbound: findUnbound(context), unread };
}

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

const SECTIONS: Record<RowKind, { title: string; intro: string; columns: string[] }> = {
	application: {
		title: "Application settings in `.env`",
		intro:
			"Compose forwards these variables to the Java containers, where Spring binds them to the setting in the second column. " +
			"The description comes from the application's configuration metadata.",
		columns: ["Variable", "Setting", "Default", "Required", "Services", "Description"],
	},
	compose: {
		title: "Compose settings in `.env`",
		intro:
			"Compose itself reads these variables, or passes them to a container that is not a Java runtime role. " +
			"The second column names the service field that each one feeds. The description comes from `.env.example`.",
		columns: ["Variable", "Feeds", "Default", "Required", "Services", "Description"],
	},
	unforwarded: {
		title: "Application settings that the supported stack fixes",
		intro:
			"The application reads these variables, but the supported stack does not take them from `.env`. " +
			"Compose sets some of them to a fixed value or from other variables, as the Default column shows. " +
			"The others keep the application default. In the supported stack, these values are fixed.",
		columns: ["Variable", "Setting", "Default", "Required", "Description"],
	},
};

/** A value that Compose sets, rather than a fallback, says so outside the code span. */
const defaultCell = (value: string): string => {
	if (!value.startsWith(COMPOSE_SETS)) {
		return code(value);
	}
	const set = value.slice(COMPOSE_SETS.length);
	return set.trim() === "" ? "Compose sets it empty" : `Compose sets ${code(set)}`;
};

export function renderPage(rows: readonly Row[]): string {
	const lines = [
		"---",
		"id: configuration-reference",
		"title: Configuration reference",
		"description: Every variable an operator can set, with its setting, default, required flag, and description.",
		"---",
		"",
		"This page lists every variable of the supported self-hosted Compose stack in `docker/self-host`.",
		"It is generated from the application's configuration files, its configuration metadata, the Compose files of that stack, and its `.env.example`.",
		"",
		"A **Required** variable stops Compose or the application when it is unset.",
		"[Configuration readiness](./configuration-readiness) reports the settings that a running instance still needs.",
		"",
	];
	for (const kind of ["application", "compose", "unforwarded"] as const) {
		const section = SECTIONS[kind];
		const sectionRows = rows.filter((row) => row.kind === kind);
		lines.push(
			`## ${section.title}`,
			"",
			section.intro,
			"",
			`| ${section.columns.join(" | ")} |`,
			`| ${section.columns.map(() => "---").join(" | ")} |`,
		);
		for (const row of sectionRows) {
			const cells = [
				code(row.variable),
				row.settings.map(code).join("<br />"),
				defaultCell(row.defaultValue),
				row.required ? "yes" : "",
				...(kind === "unforwarded" ? [] : [row.services.map(code).join(", ")]),
				cellText(row.description),
			];
			lines.push(`| ${cells.join(" | ")} |`);
		}
		lines.push("");
	}
	return `${lines.join("\n").trimEnd()}\n`;
}

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
		const variable = /^`(?<name>[A-Z][A-Z0-9_]*)`$/u.exec(cells[0] ?? "")?.groups?.name;
		if (variable !== undefined) {
			rows.push({ variable, description: cells.at(-1) ?? "" });
		}
	}
	return rows;
}

async function readAll(root: string, files: readonly string[]): Promise<[string, string][]> {
	return Promise.all(
		files.map(
			async (file) => [file, await readFile(path.join(root, file), "utf8")] as [string, string],
		),
	);
}

/** Everything but the metadata, read from the checkout. */
export async function readSources(root = REPO_ROOT): Promise<Sources> {
	const examples = await readAll(root, ENV_EXAMPLES);
	return {
		bindings: readSpringBindings(await readAll(root, APPLICATION_FILES)),
		compose: readCompose(await readAll(root, COMPOSE_FILES)),
		envExamples: examples.flatMap(([file, text]) => readEnvExample(file, text)),
	};
}

/** Every variable the sources name, which the page must therefore have a row for. */
export function requiredVariables(sources: Sources): Set<string> {
	return new Set([
		...sources.bindings.map((binding) => binding.variable),
		...sources.compose.uses.map((use) => use.placeholder.name),
		...sources.envExamples.map((key) => key.name),
	]);
}
