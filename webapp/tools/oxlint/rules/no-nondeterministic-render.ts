import { defineRule, type ESTree } from "@oxlint/plugins";

import { memberName } from "../property.ts";

/**
 * A function body, of any of the three spellings. `ESTree.Function` covers a declaration and an
 * expression together; an arrow is its own node. The two ambient members of `ESTree.Function`
 * — `TSDeclareFunction`, `TSEmptyBodyFunctionExpression` — have no body and so hold no expression
 * for this rule to have found, and are left out.
 */
type EnclosingFunction = ESTree.Function | ESTree.ArrowFunctionExpression;

const isFunction = (node: ESTree.Node): node is EnclosingFunction =>
	node.type === "FunctionDeclaration" ||
	node.type === "FunctionExpression" ||
	node.type === "ArrowFunctionExpression";

/**
 * A component or a hook, by the only signal available without type information: React's own naming
 * convention, which `react/rules-of-hooks` already relies on to decide the same question.
 */
const RENDERS = /^(?:[A-Z]|use[A-Z])/u;

/**
 * The date-fns functions that call `Date.now()` themselves instead of taking the instant as an
 * argument — every `…ToNow`, `…Today`, `…Tomorrow`, `…Yesterday` and `isThis…`, plus `isPast` and
 * `isFuture`. Each has a sibling that takes the base date (`formatDistance`, `isBefore`, `isSameDay`),
 * which is the spelling this rule asks for.
 */
const DATE_FNS_CLOCK_READERS = new Set([
	"endOfToday",
	"endOfTomorrow",
	"endOfYesterday",
	"formatDistanceToNow",
	"formatDistanceToNowStrict",
	"isFuture",
	"isPast",
	"isThisHour",
	"isThisISOWeek",
	"isThisMinute",
	"isThisMonth",
	"isThisQuarter",
	"isThisSecond",
	"isThisWeek",
	"isThisYear",
	"isToday",
	"isTomorrow",
	"isYesterday",
	"startOfToday",
	"startOfTomorrow",
	"startOfYesterday",
]);

const DATE_FNS_SOURCE = /^date-fns(?:\/|$)/u;

/**
 * The expression this rule is looking for, spelled as the message should quote it back.
 *
 * `new Date()` is here because nothing shipped can see it: oxlint has no `no-restricted-syntax`
 * (oxc#7342, closed `not_planned`) and `no-restricted-properties` matches a property read, not a
 * constructor call. The other two ride along so one rule answers the whole question in one voice.
 * An argument means the instant came from somewhere else — `new Date(iso)`, `new Date(STORY_NOW - x)`
 * — and is exactly the deterministic spelling this asks for.
 *
 * A date-fns clock reader is looked up by the local name the file imported it under, so a helper of
 * the same name written here is not mistaken for the library's.
 */
function reading(
	node: ESTree.NewExpression | ESTree.CallExpression,
	clockReaders: ReadonlyMap<string, string>,
): string | undefined {
	if (node.type === "NewExpression") {
		const isBareDate =
			node.callee.type === "Identifier" &&
			node.callee.name === "Date" &&
			node.arguments.length === 0;
		return isBareDate ? "new Date()" : undefined;
	}
	const { callee } = node;
	if (callee.type === "Identifier") {
		const imported = clockReaders.get(callee.name);
		return imported === undefined ? undefined : `${imported}()`;
	}
	if (callee.type !== "MemberExpression" || callee.object.type !== "Identifier") {
		return undefined;
	}
	const member = memberName(callee);
	if (callee.object.name === "Date" && member === "now") {
		return "Date.now()";
	}
	if (callee.object.name === "Math" && member === "random") {
		return "Math.random()";
	}
	return undefined;
}

/**
 * When the expression runs, as far as the syntax can say.
 *
 * - `module` — nothing encloses it but the file, so it runs once at import.
 * - `render` — the nearest function is named as a component or a hook, so it runs on every render.
 *   That includes a parameter default, which is evaluated on entry like any other body expression.
 * - `elsewhere` — anything else, and the rule says nothing about it.
 *
 * `elsewhere` is where the honesty is. The nearest enclosing function is the only thing the AST can
 * establish; who calls that function it cannot. So an `onClick`, a `useEffect` body, a mutation
 * callback and a `useState(() => …)` initializer all land here — none of them is a render, and a rule
 * that reported them would be wrong about the majority of the readings in this tree. A lowercase
 * module-scope helper lands here too, along with a `useMemo` body and a `cell:` render prop, which
 * *are* render-time and are the price of not guessing.
 */
type Timing = "module" | "render" | "elsewhere";

function timingOf(node: ESTree.Node): Timing {
	let current: ESTree.Node | null = node.parent;
	while (current !== null) {
		if (isFunction(current)) {
			return renders(current) ? "render" : "elsewhere";
		}
		// A class field initializer runs per instance, which is neither of the two moments here.
		if (current.type === "PropertyDefinition" || current.type === "AccessorProperty") {
			return "elsewhere";
		}
		current = current.parent;
	}
	return "module";
}

/** The name a function is declared or bound under — the only two places a rule can read one. */
function renders(fn: EnclosingFunction): boolean {
	if (fn.type !== "ArrowFunctionExpression" && fn.id !== null) {
		return RENDERS.test(fn.id.name);
	}
	const { parent } = fn;
	if (parent.type === "VariableDeclarator" && parent.id.type === "Identifier") {
		return RENDERS.test(parent.id.name);
	}
	return false;
}

export const noNondeterministicRender = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A render reads no moving value. The wall clock and the RNG are both moving, so a component that reads one renders something different every time it is asked: two components mounted in the same commit disagree with each other, a Storybook snapshot never matches the last one, and a test passes or fails on what time it is. Module load has the same problem one level up — the reading is taken once at import, off the real clock, so everything derived from it drifts with the calendar. Two shared readings exist for exactly this and are the way out: `useNow` for component code and `STORY_NOW` for stories.",
		},
		messages: {
			duringRender:
				"`{{reading}}` during render never gives the same answer twice, so stories and tests turn on what time it is. Take the time from `useNow` in `@/components/common/use-now`, or seed it once with `useState(() => …)`.",
			moduleLoad:
				"`{{reading}}` runs once at import, off the real clock, so what renders from it drifts with the calendar. In a story take it from `STORY_NOW` in `@/stories/story-clock`; in component code from `useNow`.",
			hiddenClock:
				"`{{reading}}` reads the clock itself, so it never gives the same answer twice and stories and tests turn on what time it is. Call the sibling that takes the instant — `formatDistance(date, now)`, `isBefore(date, now)`, `isSameDay(date, now)` — with `now` from `useNow` in component code and `STORY_NOW` in a story.",
		},
	},
	create(context) {
		/** Local name → imported name, for every date-fns clock reader this file imports. */
		const clockReaders = new Map<string, string>();
		const check = (node: ESTree.NewExpression | ESTree.CallExpression) => {
			const read = reading(node, clockReaders);
			if (read === undefined) {
				return;
			}
			const timing = timingOf(node);
			if (timing === "elsewhere") {
				return;
			}
			const hidden = node.type === "CallExpression" && node.callee.type === "Identifier";
			let messageId = timing === "module" ? "moduleLoad" : "duringRender";
			if (hidden) {
				messageId = "hiddenClock";
			}
			// Two readings are two edits, so each is reported where it stands.
			context.report({ node, messageId, data: { reading: read } });
		};
		return {
			ImportDeclaration(node) {
				if (!DATE_FNS_SOURCE.test(node.source.value)) {
					return;
				}
				for (const specifier of node.specifiers) {
					if (specifier.type !== "ImportSpecifier") {
						continue;
					}
					const imported =
						specifier.imported.type === "Identifier"
							? specifier.imported.name
							: specifier.imported.value;
					if (DATE_FNS_CLOCK_READERS.has(imported)) {
						clockReaders.set(specifier.local.name, imported);
					}
				}
			},
			NewExpression: check,
			CallExpression: check,
		};
	},
});
