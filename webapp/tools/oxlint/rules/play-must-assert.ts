import { defineRule, type ESTree } from "@oxlint/plugins";

import { memberName, propertyName, type VisitedProperty } from "../property.ts";

/**
 * The same list `vitest/expect-expect` carries in `.oxlintrc.json`, widened to the `expect*` and
 * `assert*` prefixes so the shared helpers in `src/test/` — `expectSettledVisible`,
 * `expectNoPageOverflow`, `expectGenuinelyDisabled` — count as the assertions they are. A Testing
 * Library `getBy*` query counts too: it throws when it finds nothing. That the config's list stays
 * inside this one is pinned by `play-must-assert.test.ts`.
 *
 * `*` matches within one path segment, `**` across them, so `**.getBy*` reaches both
 * `canvas.getByRole` and `within(row).getByRole`.
 */
export const ASSERT_FUNCTION_NAMES = [
	"expect*",
	"**.expect*",
	"assert*",
	"**.assert*",
	"getBy*",
	"**.getBy*",
	"getAllBy*",
	"**.getAllBy*",
	"findBy*",
	"**.findBy*",
	"findAllBy*",
	"**.findAllBy*",
];

const escaped = (literal: string) => literal.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);

export const asRegExp = (glob: string) =>
	new RegExp(
		`^${glob
			.split("**")
			.map((crossing) => crossing.split("*").map(escaped).join("[^.]*"))
			.join(".*")}$`,
		"u",
	);

const ASSERT_PATTERNS = ASSERT_FUNCTION_NAMES.map(asRegExp);

/** A `play:` holding a function literal. `play: sharedPlay` is somebody else's body. */
const playFunction = (node: VisitedProperty) =>
	propertyName(node) === "play" &&
	(node.value.type === "ArrowFunctionExpression" || node.value.type === "FunctionExpression");

/**
 * `Open.play?.(context)` runs another story's play and inherits its assertions. A glob would have to
 * spell this `**.play`, which also matches `audio.play()` and `videoRef.current.play()` — a media
 * element, asserting nothing. Only a story qualifies, and a story is a capitalised export.
 */
const delegatesToStoryPlay = (callee: ESTree.Node) =>
	callee.type === "MemberExpression" &&
	memberName(callee) === "play" &&
	callee.object.type === "Identifier" &&
	/^[A-Z]/u.test(callee.object.name);

export const playMustAssert = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A story's `play` function must end in an assertion. Without one it passes as long as nothing throws, which proves the clicks landed and not that they did anything.",
		},
		messages: {
			noAssertion:
				"This play drives the component but never asserts on the result. Assert what the interaction was for: a `getBy*` query for the element it should have produced, or `expect` on the value it should have changed.",
		},
	},
	create(context) {
		// A stack, not a flag: plays nest (a `step` callback holds one), and two stories side by side
		// must not lend each other an assertion.
		const plays: { key: ESTree.Node; asserted: boolean }[] = [];

		return {
			Property(node) {
				if (playFunction(node)) {
					plays.push({ key: node.key, asserted: false });
				}
			},
			"Property:exit"(node) {
				if (!playFunction(node)) {
					return;
				}
				const play = plays.pop();
				if (play && !play.asserted) {
					context.report({ node: play.key, messageId: "noAssertion" });
				}
			},
			CallExpression(node) {
				const play = plays.at(-1);
				if (!play || play.asserted) {
					return;
				}
				if (delegatesToStoryPlay(node.callee)) {
					play.asserted = true;
					return;
				}
				const callee = context.sourceCode.getText(node.callee);
				play.asserted = ASSERT_PATTERNS.some((pattern) => pattern.test(callee));
			},
		};
	},
});
