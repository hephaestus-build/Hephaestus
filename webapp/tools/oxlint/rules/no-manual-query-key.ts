import { defineRule, type ESTree } from "@oxlint/plugins";

import { propertyName } from "../property.ts";

/** The module the generated client exports its key helpers from. */
const GENERATED = "@/api/@tanstack/react-query.gen";

/** `[…] as const` and `[…] satisfies readonly unknown[]` state a type; the array is still an array. */
const unwrapStatedType = (value: ESTree.Node): ESTree.Node =>
	value.type === "TSAsExpression" || value.type === "TSSatisfiesExpression"
		? unwrapStatedType(value.expression)
		: value;

export const noManualQueryKey = defineRule({
	meta: {
		type: "problem",
		docs: {
			description: `A \`queryKey\` comes from the generated \`*QueryKey()\` helper, never from a hand-written array. The helpers in \`${GENERATED}\` are regenerated from the OpenAPI spec, and a hand-built key that stops matching theirs fails nothing: the invalidation simply becomes a no-op and the UI keeps serving what it already had.`,
		},
		messages: {
			handWritten:
				"Pass the generated key — `getThingQueryKey({ path: … })` — or spread the generated options, so this stays in step with what the query was cached under.",
		},
	},
	create(context) {
		return {
			Property(node) {
				if (propertyName(node) !== "queryKey") {
					return;
				}
				// A shorthand, an identifier or a call is already someone else's value; only an array
				// literal is this file claiming to know the generated shape.
				const value = unwrapStatedType(node.value);
				if (value.type !== "ArrayExpression") {
					return;
				}
				context.report({ node: value, messageId: "handWritten" });
			},
		};
	},
});
