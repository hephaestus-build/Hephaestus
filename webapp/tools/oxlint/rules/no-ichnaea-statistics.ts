import { type Context, defineRule, type ESTree, type Scope, type Variable } from "@oxlint/plugins";

/**
 * A module specifier that loads Ichnaea's statistics or calibration library: the package under any
 * scope, so the rule does not depend on the name it is published under, and the same files reached
 * through `node_modules` by a path. A local directory that happens to be called `ichnaea` is not the
 * package.
 */
const ICHNAEA_STATISTICS =
	/^(?:(?:@[^/]+\/)?ichnaea|(?:.*\/)?node_modules\/(?:@[^/]+\/)?ichnaea)\/(?:[^/]+\/)*(?:stats|calibration)(?:\/|$)/u;

/** Any node the parser calls an `Identifier`: a reference, a binding or a property name. */
type Identifier = Extract<ESTree.Node, { type: "Identifier" }>;

/** The variable that `identifier` reads, if this file declares it. */
function variableOf(context: Context, identifier: Identifier): Variable | undefined {
	for (let scope: Scope | null = context.sourceCode.getScope(identifier); scope !== null;) {
		const variable = scope.set.get(identifier.name);
		if (variable !== undefined) {
			return variable;
		}
		scope = scope.upper;
	}
	return undefined;
}

/**
 * The value that a `const` binding of `identifier` always holds. A destructuring pattern selects part
 * of its initializer, and a `let` can change, so neither has one the rule can read.
 */
function constValue(context: Context, identifier: Identifier): ESTree.Node | undefined {
	const definitions = variableOf(context, identifier)?.defs ?? [];
	const declarator = definitions[0]?.node;
	return definitions.length === 1 &&
		declarator?.type === "VariableDeclarator" &&
		declarator.id.type === "Identifier" &&
		declarator.parent.type === "VariableDeclaration" &&
		declarator.parent.kind === "const"
		? (declarator.init ?? undefined)
		: undefined;
}

/** The name of a non-computed member, as in `object.name`. */
const memberName = (node: ESTree.Node): string | undefined =>
	node.type === "MemberExpression" && !node.computed && node.property.type === "Identifier"
		? node.property.name
		: undefined;

/** The builtin that exports `createRequire`, under either of its names. */
const NODE_MODULE = new Set(["node:module", "module"]);

/** The builtin that exports `getBuiltinModule`, under either of its names. */
const NODE_PROCESS = new Set(["node:process", "process"]);

/** The name a property key or import specifier states, when it states one. */
function statedName(node: ESTree.Node): string | undefined {
	if (node.type === "Identifier") {
		return node.name;
	}
	return node.type === "Literal" && typeof node.value === "string" ? node.value : undefined;
}

/** What an import binds `identifier` to: the module and the export, `*` for a namespace. */
function importedBinding(
	context: Context,
	identifier: Identifier,
): { module: string; name: string } | undefined {
	const definition = variableOf(context, identifier)?.defs[0];
	if (definition?.type !== "ImportBinding" || definition.parent?.type !== "ImportDeclaration") {
		return undefined;
	}
	const specifier = definition.node;
	let name: string | undefined = "*";
	if (specifier.type === "ImportSpecifier") {
		name = statedName(specifier.imported);
	} else if (specifier.type === "ImportDefaultSpecifier") {
		name = "default";
	}
	return name === undefined ? undefined : { module: definition.parent.source.value, name };
}

/**
 * True when `node` evaluates to the whole of one of the `builtin` modules: a namespace or default
 * import, one of the export names in `whole`, `await import(…)` of it, or a `const` holding one.
 */
function isBuiltin(
	context: Context,
	node: ESTree.Node,
	builtin: ReadonlySet<string>,
	whole: ReadonlySet<string>,
	depth = 0,
): boolean {
	if (depth > 8) {
		return false;
	}
	if (node.type === "AwaitExpression") {
		return (
			node.argument.type === "ImportExpression" &&
			builtin.has(staticText(context, node.argument.source) ?? "")
		);
	}
	if (node.type !== "Identifier") {
		return false;
	}
	const imported = importedBinding(context, node);
	if (imported !== undefined) {
		return builtin.has(imported.module) && whole.has(imported.name);
	}
	const value = constValue(context, node);
	return value !== undefined && isBuiltin(context, value, builtin, whole, depth + 1);
}

/** The export names that stand for the whole of `node:module`; `Module` carries `createRequire`. */
const WHOLE_NODE_MODULE = new Set(["*", "default", "Module"]);

/** The export names that stand for the whole of `node:process`. */
const WHOLE_NODE_PROCESS = new Set(["*", "default"]);

/**
 * True when `node` is Node's `process`: the global that no declaration in this file shadows, the
 * `node:process` builtin, or a `const` holding either.
 */
function isProcess(context: Context, node: ESTree.Node, depth = 0): boolean {
	if (depth > 8) {
		return false;
	}
	if (isBuiltin(context, node, NODE_PROCESS, WHOLE_NODE_PROCESS)) {
		return true;
	}
	if (node.type !== "Identifier") {
		return false;
	}
	const value = constValue(context, node);
	if (value !== undefined) {
		return isProcess(context, value, depth + 1);
	}
	return node.name === "process" && (variableOf(context, node)?.defs.length ?? 0) === 0;
}

/**
 * True when `callee` is the export `name` of one of the `builtin` modules: imported under any local
 * name, destructured from the module under any local name, read from it as a member, or a `const`
 * holding one of those.
 */
function isBuiltinExport(
	context: Context,
	callee: ESTree.Node,
	name: string,
	isModule: (node: ESTree.Node) => boolean,
	builtin: ReadonlySet<string>,
	depth = 0,
): boolean {
	if (depth > 8) {
		return false;
	}
	if (memberName(callee) === name && callee.type === "MemberExpression") {
		return isModule(callee.object);
	}
	if (callee.type !== "Identifier") {
		return false;
	}
	const imported = importedBinding(context, callee);
	if (imported !== undefined) {
		return builtin.has(imported.module) && imported.name === name;
	}
	const declarator = variableOf(context, callee)?.defs[0]?.node;
	if (
		declarator?.type === "VariableDeclarator" &&
		declarator.id.type === "ObjectPattern" &&
		declarator.parent.type === "VariableDeclaration" &&
		declarator.parent.kind === "const" &&
		declarator.init !== null
	) {
		const selected = declarator.id.properties.find(
			(property) =>
				property.type === "Property" &&
				!property.computed &&
				property.value.type === "Identifier" &&
				property.value.name === callee.name,
		);
		return (
			selected?.type === "Property" &&
			statedName(selected.key) === name &&
			isModule(declarator.init)
		);
	}
	const value = constValue(context, callee);
	return value !== undefined && isBuiltinExport(context, value, name, isModule, builtin, depth + 1);
}

/**
 * True when `node` evaluates to `node:module`: the builtin, `process.getBuiltinModule("node:module")`,
 * or a `const` holding either.
 */
function isNodeModule(context: Context, node: ESTree.Node, depth = 0): boolean {
	if (depth > 8) {
		return false;
	}
	if (node.type === "CallExpression") {
		return (
			NODE_MODULE.has(staticText(context, node.arguments[0]) ?? "") &&
			isBuiltinExport(
				context,
				node.callee,
				"getBuiltinModule",
				(object) => isProcess(context, object),
				NODE_PROCESS,
			)
		);
	}
	const value = node.type === "Identifier" ? constValue(context, node) : undefined;
	return value === undefined
		? isBuiltin(context, node, NODE_MODULE, WHOLE_NODE_MODULE)
		: isNodeModule(context, value, depth + 1);
}

/** True when `callee` is Node's `createRequire`, however this file reached it. */
const isCreateRequire = (context: Context, callee: ESTree.Node): boolean =>
	isBuiltinExport(
		context,
		callee,
		"createRequire",
		(object) => isNodeModule(context, object),
		NODE_MODULE,
	);

/**
 * True when calling `callee` loads or resolves the module its first argument names: the global
 * `require`, a function that `createRequire` made, `import.meta.resolve`, the `resolve` of either
 * loader, and a `const` that holds any of them.
 */
function isLoader(context: Context, callee: ESTree.Node, depth = 0): boolean {
	if (depth > 8) {
		return false;
	}
	if (callee.type === "Identifier") {
		const value = constValue(context, callee);
		if (value === undefined) {
			// No declaration in this file: Node's `require`, whatever the configured globals say.
			return callee.name === "require" && (variableOf(context, callee)?.defs.length ?? 0) === 0;
		}
		return value.type === "CallExpression"
			? isCreateRequire(context, value.callee)
			: isLoader(context, value, depth + 1);
	}
	if (callee.type === "CallExpression") {
		return isCreateRequire(context, callee.callee);
	}
	if (memberName(callee) === "resolve" && callee.type === "MemberExpression") {
		const { object } = callee;
		return (
			(object.type === "MetaProperty" && object.meta.name === "import") ||
			isLoader(context, object, depth + 1)
		);
	}
	return false;
}

/**
 * The text a specifier expression always has: a string literal; a template literal or a `+` whose
 * parts all have one; or a `const` in this file that holds one, read through a type assertion.
 */
function staticText(
	context: Context,
	node: ESTree.Node | null | undefined,
	depth = 0,
): string | undefined {
	if (node === null || node === undefined || depth > 8) {
		return undefined;
	}
	if (node.type === "Literal") {
		return typeof node.value === "string" ? node.value : undefined;
	}
	if (node.type === "TemplateLiteral") {
		const parts = node.expressions.map((expression) => staticText(context, expression, depth + 1));
		if (parts.some((part) => part === undefined)) {
			return undefined;
		}
		return node.quasis
			.map((quasi, index) => `${quasi.value.cooked ?? ""}${parts[index] ?? ""}`)
			.join("");
	}
	if (node.type === "BinaryExpression" && node.operator === "+") {
		const left = staticText(context, node.left, depth + 1);
		const right = staticText(context, node.right, depth + 1);
		return left === undefined || right === undefined ? undefined : `${left}${right}`;
	}
	if (
		node.type === "TSAsExpression" ||
		node.type === "TSSatisfiesExpression" ||
		node.type === "TSNonNullExpression" ||
		node.type === "TSTypeAssertion"
	) {
		return staticText(context, node.expression, depth + 1);
	}
	return node.type === "Identifier"
		? staticText(context, constValue(context, node), depth + 1)
		: undefined;
}

export const noIchnaeaStatistics = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"Ichnaea owns the analysis plan, the run, the decision and the report. Hephaestus reads its verdict and its report, and never computes a statistic with Ichnaea's `stats` or `calibration` library. A number computed here is a second report path: it can disagree with the decision that the report states, and nothing checks it against the plan.",
		},
		messages: {
			statistics:
				"`{{specifier}}` is Ichnaea's statistics. Read the result from Ichnaea's decision or report instead, or add what is missing to Ichnaea, so that one report path states every number.",
		},
	},
	create(context) {
		const check = (node: ESTree.Node, source: ESTree.Node | null | undefined) => {
			const specifier = staticText(context, source);
			if (specifier !== undefined && ICHNAEA_STATISTICS.test(specifier)) {
				context.report({ node, messageId: "statistics", data: { specifier } });
			}
		};
		return {
			ImportDeclaration(node) {
				check(node, node.source);
			},
			ExportNamedDeclaration(node) {
				check(node, node.source);
			},
			ExportAllDeclaration(node) {
				check(node, node.source);
			},
			ImportExpression(node) {
				check(node, node.source);
			},
			TSImportType(node) {
				check(node, node.source);
			},
			TSImportEqualsDeclaration(node) {
				if (node.moduleReference.type === "TSExternalModuleReference") {
					check(node, node.moduleReference.expression);
				}
			},
			CallExpression(node) {
				if (isLoader(context, node.callee)) {
					check(node, node.arguments[0]);
				}
			},
		};
	},
});
