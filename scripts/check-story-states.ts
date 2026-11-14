/**
 * A component whose props carry a loading, error or empty state ships a story that sets each state it
 * can show (AGENTS.md § Hit every surface, UI states). `webapp/AGENTS.md` § Component API and
 * Storybook owns the rule: which props carry a state, and when a story covers it.
 *
 * The check reads declarations with Oxc, as the sibling gates do. On the component side, it follows a
 * props type through local aliases, interfaces, intersections, unions, generic arguments and relative
 * or `@/` imports. It descends into a member only when the member is named `state` or `status`, so a
 * nested domain object cannot add a signal. Generated wire types (`@/api/`) are not read. On the story
 * side, it reads the properties and JSX attributes that each exported story and the meta set, at any
 * depth, and follows the module constants and stories that a story references or spreads.
 */
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

import { parseSync } from "vite-plus";

import { asRecord, asString, isRecord } from "./lib/json.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const COMPONENT_TREE = "webapp/src/components";

export const STATES = ["loading", "error", "empty"] as const;
export type State = (typeof STATES)[number];

/** What the props name, and what the story names must contain, per state. */
const PROP_NAMES: Record<State, readonly string[]> = {
	loading: ["isLoading", "loading"],
	error: ["error", "isError"],
	empty: [],
};
const LITERALS: Record<State, readonly string[]> = {
	loading: ["loading"],
	error: ["error"],
	empty: ["empty"],
};
/** A member that ends in `Error`, such as `serverError`, carries an error state too. */
const ERROR_MEMBER = /^[a-z]+Error$/u;
/** Members whose type is a state, so a nested domain object cannot add a signal. */
const STATE_MEMBERS = new Set(["state", "status"]);

type Node = Record<string, unknown>;
type Module = { src: Node; types: Map<string, Node>; imports: Map<string, string> };

const node = (value: unknown): Node | undefined => (isRecord(value) ? value : undefined);
const list = (value: unknown): readonly Node[] =>
	Array.isArray(value) ? value.filter(isRecord) : [];
const nameOf = (value: unknown): string | undefined => {
	const identifier = node(value);
	return identifier?.type === "Identifier" && typeof identifier.name === "string"
		? identifier.name
		: undefined;
};

/** The repository-relative file a module specifier names, or undefined for a package or wire type. */
async function resolveImport(
	from: string,
	specifier: string,
	exists: (file: string) => Promise<boolean>,
): Promise<string | undefined> {
	let base: string;
	if (specifier.startsWith("@/") && !specifier.startsWith("@/api/")) {
		base = path.posix.join("webapp/src", specifier.slice(2));
	} else if (specifier.startsWith(".")) {
		base = path.posix.join(path.posix.dirname(from), specifier);
	} else {
		return undefined;
	}
	for (const candidate of [`${base}.ts`, `${base}.tsx`, `${base}/index.ts`, `${base}/index.tsx`]) {
		if (await exists(candidate)) {
			return candidate;
		}
	}
	return undefined;
}

class Program {
	readonly #modules = new Map<string, Module | undefined>();
	readonly #root: string;

	constructor(root: string) {
		this.#root = root;
	}

	async exists(file: string): Promise<boolean> {
		return (await this.module(file)) !== undefined;
	}

	async module(file: string): Promise<Module | undefined> {
		if (this.#modules.has(file)) {
			return this.#modules.get(file);
		}
		let source: string;
		try {
			source = await readFile(path.join(this.#root, file), "utf8");
		} catch {
			this.#modules.set(file, undefined);
			return undefined;
		}
		const parsed = parseSync(file, source);
		if (parsed.errors.length > 0) {
			throw new Error(
				`${file}: cannot parse source: ${parsed.errors.map((error) => error.message).join("; ")}`,
			);
		}
		const program = asRecord(parsed.program, `${file} program`);
		const types = new Map<string, Node>();
		const imports = new Map<string, string>();
		const pending: { local: string; specifier: string }[] = [];
		for (const statement of list(program.body)) {
			const declaration = node(statement.declaration) ?? statement;
			const name = nameOf(declaration.id);
			if (
				name !== undefined &&
				(declaration.type === "TSInterfaceDeclaration" ||
					declaration.type === "TSTypeAliasDeclaration")
			) {
				types.set(name, declaration);
			}
			if (
				statement.type === "ImportDeclaration" &&
				typeof node(statement.source)?.value === "string"
			) {
				const specifier = asString(node(statement.source)?.value, `${file} import source`);
				for (const item of list(statement.specifiers)) {
					const local = nameOf(item.local);
					if (item.type === "ImportSpecifier" && local !== undefined) {
						pending.push({ local, specifier });
					}
				}
			}
		}
		const module: Module = { src: program, types, imports };
		this.#modules.set(file, module);
		for (const { local, specifier } of pending) {
			const target = await resolveImport(file, specifier, (candidate) => this.exists(candidate));
			if (target !== undefined) {
				imports.set(local, target);
			}
		}
		return module;
	}

	/** The states that a type's declarations name, following references where they resolve. */
	async signals(
		type: unknown,
		module: Module,
		found: Set<State>,
		seen: Set<unknown>,
		inState = false,
	): Promise<void> {
		const current = node(type);
		if (current === undefined || seen.has(current)) {
			return;
		}
		seen.add(current);
		switch (current.type) {
			case "TSTypeAnnotation":
				await this.signals(current.typeAnnotation, module, found, seen, inState);
				break;
			case "TSUnionType":
			case "TSIntersectionType":
				for (const member of list(current.types)) {
					await this.signals(member, module, found, seen, inState);
				}
				break;
			case "TSLiteralType": {
				const value = node(current.literal)?.value;
				if (inState && typeof value === "string") {
					for (const state of STATES) {
						if (LITERALS[state].includes(value)) {
							found.add(state);
						}
					}
				}
				break;
			}
			case "TSTypeLiteral":
				await this.members(list(current.members), module, found, seen);
				break;
			case "TSInterfaceDeclaration":
				await this.members(list(node(current.body)?.body), module, found, seen);
				for (const parent of list(current.extends)) {
					await this.reference(parent.expression, parent.typeArguments, module, found, seen);
				}
				break;
			case "TSTypeAliasDeclaration":
				await this.signals(current.typeAnnotation, module, found, seen, inState);
				break;
			case "TSTypeReference":
				await this.reference(current.typeName, current.typeArguments, module, found, seen, inState);
				break;
			case "TSIndexedAccessType":
				await this.signals(current.objectType, module, found, seen, inState);
				break;
			default:
		}
	}

	async members(
		members: readonly Node[],
		module: Module,
		found: Set<State>,
		seen: Set<unknown>,
	): Promise<void> {
		for (const member of members) {
			const name = nameOf(member.key);
			if (member.type !== "TSPropertySignature" || name === undefined) {
				continue;
			}
			for (const state of STATES) {
				if (PROP_NAMES[state].includes(name) || (state === "error" && ERROR_MEMBER.test(name))) {
					found.add(state);
				}
			}
			if (STATE_MEMBERS.has(name)) {
				await this.signals(member.typeAnnotation, module, found, seen, true);
			}
		}
	}

	async reference(
		name: unknown,
		typeArguments: unknown,
		module: Module,
		found: Set<State>,
		seen: Set<unknown>,
		inState = false,
	): Promise<void> {
		const identifier = nameOf(name);
		const args = list(node(typeArguments)?.params);
		if ((identifier === "Exclude" || identifier === "Extract") && args.length === 2) {
			// Exclude drops the states its second argument names, Extract keeps only those.
			const from = new Set<State>();
			const by = new Set<State>();
			await this.signals(args[0], module, from, new Set(), inState);
			await this.signals(args[1], module, by, new Set(), inState);
			for (const state of from) {
				if (by.has(state) === (identifier === "Extract")) {
					found.add(state);
				}
			}
			return;
		}
		for (const argument of args) {
			await this.signals(argument, module, found, seen, inState);
		}
		if (identifier === undefined) {
			return;
		}
		const local = module.types.get(identifier);
		if (local !== undefined) {
			await this.signals(local, module, found, seen, inState);
			return;
		}
		const target = module.imports.get(identifier);
		const imported = target === undefined ? undefined : await this.module(target);
		const declaration = imported?.types.get(identifier);
		if (imported !== undefined && declaration !== undefined) {
			await this.signals(declaration, imported, found, seen, inState);
		}
	}

	/** Union of the states carried by every exported component's props in a file. */
	async componentSignals(file: string): Promise<ReadonlySet<State>> {
		const module = await this.module(file);
		const found = new Set<State>();
		if (module === undefined) {
			return found;
		}
		for (const statement of list(module.src.body)) {
			if (statement.type !== "ExportNamedDeclaration") {
				continue;
			}
			for (const component of exportedComponents(node(statement.declaration))) {
				const props = node(list(component.params)[0]);
				await this.signals(props?.typeAnnotation, module, found, new Set());
			}
		}
		return found;
	}
}

/** Function declarations and `const X = (props) => …` (also through `memo` and `forwardRef`). */
function exportedComponents(declaration: Node | undefined): readonly Node[] {
	if (declaration === undefined) {
		return [];
	}
	const isFunction = (value: Node | undefined): value is Node =>
		value?.type === "FunctionDeclaration" ||
		value?.type === "FunctionExpression" ||
		value?.type === "ArrowFunctionExpression";
	if (declaration.type === "FunctionDeclaration") {
		return /^[A-Z]/u.test(nameOf(declaration.id) ?? "") ? [declaration] : [];
	}
	if (declaration.type !== "VariableDeclaration") {
		return [];
	}
	return list(declaration.declarations).flatMap((declarator) => {
		if (!/^[A-Z]/u.test(nameOf(declarator.id) ?? "")) {
			return [];
		}
		const init = node(declarator.init);
		return isFunction(init) ? [init] : list(init?.arguments).filter(isFunction);
	});
}

/** A value that sets a state: anything but `false`, `null`, `undefined` and an absent value. */
function setsValue(value: Node | undefined): boolean {
	if (value === undefined) {
		return false;
	}
	if (value.type === "Literal") {
		return value.value !== false && value.value !== null;
	}
	return !(value.type === "Identifier" && value.name === "undefined");
}

/** The state that one property or JSX attribute of a story sets, by the rules that read the props. */
function stateOf(name: string, value: Node | undefined, attribute: boolean): State | undefined {
	// A bare JSX attribute (`<Table isLoading />`) is `true`.
	const bare = attribute && value === undefined;
	if (PROP_NAMES.loading.includes(name)) {
		return bare || (value?.type === "Literal" && value.value === true) ? "loading" : undefined;
	}
	if (PROP_NAMES.error.includes(name) || ERROR_MEMBER.test(name)) {
		return bare || setsValue(value) ? "error" : undefined;
	}
	if (STATE_MEMBERS.has(name) && value?.type === "Literal" && typeof value.value === "string") {
		return STATES.find((state) => LITERALS[state].includes(String(value.value)));
	}
	return undefined;
}

/**
 * The states that a story's own code sets: every property and JSX attribute in its args and its
 * `render`, at any depth. `references` collects the names it uses, so the module constants and
 * stories it spreads or passes count too. A story's name decides nothing.
 */
function setStates(root: Node, references: Set<string>): Set<State> {
	const found = new Set<State>();
	const visit = (value: unknown): void => {
		if (Array.isArray(value)) {
			value.forEach(visit);
			return;
		}
		const current = node(value);
		if (current === undefined) {
			return;
		}
		if (current.type === "Property") {
			const key = node(current.key);
			const name = nameOf(key) ?? (typeof key?.value === "string" ? key.value : undefined);
			const state = name === undefined ? undefined : stateOf(name, node(current.value), false);
			if (state !== undefined) {
				found.add(state);
			}
		} else if (current.type === "JSXAttribute") {
			const name = asString(asRecord(current.name)?.name);
			const raw = node(current.value);
			const value = raw?.type === "JSXExpressionContainer" ? node(raw.expression) : raw;
			const state = name === undefined ? undefined : stateOf(name, value, true);
			if (state !== undefined) {
				found.add(state);
			}
		} else if (current.type === "Identifier" && typeof current.name === "string") {
			// `args: LOADING_ARGS`, `...Loading` or `...Loading.args`: a module constant's states count too.
			references.add(current.name);
		}
		for (const [key, child] of Object.entries(current)) {
			const skipped =
				key === "type" ||
				key === "start" ||
				key === "end" ||
				// A property's key and a member's name are names, not references.
				(current.type === "Property" && key === "key") ||
				(current.type === "MemberExpression" && key === "property");
			if (!skipped) {
				visit(child);
			}
		}
	};
	visit(root);
	return found;
}

/** The states that the sibling stories set, through their args, their `render` or the meta's args. */
async function storyStates(storyFile: string, program: Program): Promise<ReadonlySet<State>> {
	const module = await program.module(storyFile);
	// Every module constant, exported story or not, so that a story's reference to one resolves.
	const constants = new Map<string, { states: Set<State>; references: Set<string> }>();
	const exported: string[] = [];
	const covered = new Set<State>();
	for (const statement of list(module?.src.body)) {
		const declared =
			statement.type === "ExportNamedDeclaration" ? node(statement.declaration) : statement;
		if (declared?.type !== "VariableDeclaration") {
			continue;
		}
		for (const declarator of list(declared.declarations)) {
			const name = nameOf(declarator.id);
			const init = node(declarator.init);
			if (name === undefined || init === undefined) {
				continue;
			}
			const references = new Set<string>();
			constants.set(name, { states: setStates(init, references), references });
			// The meta's args are every story's defaults.
			if (name === "meta" || statement.type === "ExportNamedDeclaration") {
				exported.push(name);
			}
		}
	}
	const resolve = (name: string, seen: Set<string>): Set<State> => {
		const constant = constants.get(name);
		if (constant === undefined || seen.has(name)) {
			return new Set();
		}
		seen.add(name);
		const states = new Set(constant.states);
		for (const reference of constant.references) {
			resolve(reference, seen).forEach((state) => states.add(state));
		}
		return states;
	};
	for (const name of exported) {
		resolve(name, new Set()).forEach((state) => covered.add(state));
	}
	return covered;
}

async function componentFiles(root: string): Promise<string[]> {
	const entries = await readdir(path.join(root, COMPONENT_TREE), {
		recursive: true,
		withFileTypes: true,
	});
	return entries
		.filter(
			(entry) =>
				entry.isFile() &&
				/^[A-Z][^.]*\.tsx$/u.test(entry.name) &&
				!/\.(?:stories|test)\.tsx$/u.test(entry.name),
		)
		.map((entry) =>
			path.relative(root, path.join(entry.parentPath, entry.name)).split(path.sep).join("/"),
		)
		.filter((file) => !file.startsWith(`${COMPONENT_TREE}/ui/`))
		.toSorted();
}

export type Measurement = {
	/** Data-region components: the states their props carry. */
	regions: ReadonlyMap<string, ReadonlySet<State>>;
	/** The carried states that no sibling story name covers. A component with none left is absent. */
	missing: ReadonlyMap<string, readonly State[]>;
};

export async function measure(root: string): Promise<Measurement> {
	const files = await componentFiles(root);
	if (files.length === 0) {
		throw new Error(
			`No component files found under ${COMPONENT_TREE} — this check would pass unchecked.`,
		);
	}
	const program = new Program(root);
	const regions = new Map<string, ReadonlySet<State>>();
	const missing = new Map<string, readonly State[]>();
	for (const file of files) {
		const carried = await program.componentSignals(file);
		if (carried.size === 0) {
			continue;
		}
		regions.set(file, carried);
		const covered = await storyStates(file.replace(/\.tsx$/u, ".stories.tsx"), program);
		const absent = STATES.filter((state) => carried.has(state) && !covered.has(state));
		if (absent.length > 0) {
			missing.set(file, absent);
		}
	}
	return { regions, missing };
}

if (import.meta.main) {
	const { regions, missing } = await measure(REPO_ROOT);
	if (regions.size === 0) {
		console.error("No data-region components found — this check would pass unchecked.");
		process.exit(1);
	}
	if (missing.size > 0) {
		console.error(
			"A component whose props carry a loading, error, or empty state needs a sibling story for it:\n",
		);
		for (const [file, states] of missing) {
			console.error(`  ${file}: add a ${states.join(" and a ")} story.`);
		}
		process.exit(1);
	}
	console.log(
		`check-story-states: ${regions.size} data-region components, every state has a story.`,
	);
}
