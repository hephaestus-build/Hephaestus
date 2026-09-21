import { defineRule, type ESTree } from "@oxlint/plugins";

import { memberName } from "../property.ts";

/**
 * The four Testing Library query families that fail by themselves: `getBy*` and `getAllBy*` throw,
 * `findBy*` and `findAllBy*` reject. `queryBy*` is deliberately absent — it returns null, so there
 * the matcher is the assertion.
 */
const THROWING_QUERY = /^(?:get|find)(?:All)?By[A-Z]/u;

const VACUOUS_MATCHERS = new Set(["toBeInTheDocument", "toBeTruthy", "toBeDefined"]);

/** The name a call is made under: `getByRole`, `canvas.getByRole` and `within(row).getByRole` all name the same query. */
const calleeName = (callee: ESTree.Node): string | undefined => {
	if (callee.type === "Identifier") {
		return callee.name;
	}
	if (callee.type === "MemberExpression") {
		return memberName(callee);
	}
	return undefined;
};

export const noRedundantInTheDocument = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"Do not wrap a query that already throws in a matcher that can never fail. In a story, where jest-dom is registered, `toBeVisible()` is a real strengthening — but it reads a just-opened Base UI overlay as invisible for one frame, so reach for `expectSettledVisible` from `@/test/overlay` when the target sits inside one. A Vitest file has neither matcher and asserts on the value instead.",
		},
		messages: {
			vacuous:
				"`getBy*`, `getAllBy*`, `findBy*` and `findAllBy*` all fail on their own when nothing matches, so this matcher asserts nothing. Drop the `expect(…)` wrapper and let the query stand as the assertion.",
		},
	},
	create(context) {
		return {
			CallExpression(node) {
				const matcher = node.callee;
				if (matcher.type !== "MemberExpression") {
					return;
				}
				const matcherName = memberName(matcher);
				if (matcherName === undefined || !VACUOUS_MATCHERS.has(matcherName)) {
					return;
				}

				const expectCall = matcher.object;
				if (expectCall.type !== "CallExpression") {
					return;
				}
				if (expectCall.callee.type !== "Identifier" || expectCall.callee.name !== "expect") {
					return;
				}

				// `expect(await canvas.findByRole(…))` puts the query one level down; the await is the
				// caller's business, not a different subject.
				const [argument] = expectCall.arguments;
				const subject = argument?.type === "AwaitExpression" ? argument.argument : argument;
				if (subject?.type !== "CallExpression") {
					return;
				}

				const query = calleeName(subject.callee);
				if (query === undefined || !THROWING_QUERY.test(query)) {
					return;
				}

				context.report({ node: subject, messageId: "vacuous" });
			},
		};
	},
});
