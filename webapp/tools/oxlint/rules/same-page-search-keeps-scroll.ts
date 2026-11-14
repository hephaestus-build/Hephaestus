import { defineRule, type ESTree } from "@oxlint/plugins";

type Property = ESTree.ObjectExpression["properties"][number];

const keyOf = (property: Property): string | undefined => {
	if (property.type !== "Property" || property.computed) {
		return undefined;
	}
	const { key } = property;
	if (key.type === "Identifier") {
		return key.name;
	}
	return key.type === "Literal" && typeof key.value === "string" ? key.value : undefined;
};

/** `navigate(...)` or `router.navigate(...)`: the router's imperative navigation. */
function isNavigateCall(callee: ESTree.CallExpression["callee"]): boolean {
	if (callee.type === "Identifier") {
		return callee.name === "navigate";
	}
	return (
		callee.type === "MemberExpression" &&
		callee.property.type === "Identifier" &&
		callee.property.name === "navigate"
	);
}

const isCurrentRoute = (value: ESTree.Node | null | undefined): boolean =>
	value?.type === "Literal" && value.value === ".";

/**
 * The navigation stays on the page it starts on: it writes only the search, with no other path, no
 * other params and no `href`. A spread could carry any of these, so an object with one is left alone.
 */
function staysOnPage(names: Map<string, ESTree.Node | null>, spread: boolean): boolean {
	if (spread || !names.has("search") || names.has("resetScroll")) {
		return false;
	}
	if (names.has("params") || names.has("href") || names.has("mask")) {
		return false;
	}
	return !names.has("to") || isCurrentRoute(names.get("to"));
}

export const samePageSearchKeepsScroll = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A search change on the page the reader is on keeps their scroll position. TanStack Router resets scroll on every commit, a search-only one included, so a filter, a tab or a search box halfway down a page throws the reader back to the top. `useSearchState` (`lib/search-params.ts`) passes `resetScroll: false`.",
		},
		messages: {
			reset:
				"This search change stays on the page, and the router scrolls to the top unless told otherwise. Write it with `useSearchState` from `@/lib/search-params`, or pass `resetScroll` to say what the reader should see.",
		},
	},
	create(context) {
		return {
			CallExpression(node) {
				const [options] = node.arguments;
				if (!isNavigateCall(node.callee) || options?.type !== "ObjectExpression") {
					return;
				}
				const names = new Map<string, ESTree.Node | null>();
				let spread = false;
				for (const property of options.properties) {
					if (property.type === "SpreadElement") {
						spread = true;
						continue;
					}
					const key = keyOf(property);
					if (key !== undefined) {
						names.set(key, property.value);
					}
				}
				if (staysOnPage(names, spread)) {
					context.report({ node, messageId: "reset" });
				}
			},
			JSXOpeningElement(node) {
				const names = new Map<string, ESTree.Node | null>();
				let spread = false;
				for (const attribute of node.attributes) {
					if (attribute.type === "JSXSpreadAttribute") {
						spread = true;
						continue;
					}
					if (attribute.name.type !== "JSXIdentifier") {
						continue;
					}
					const { value } = attribute;
					names.set(
						attribute.name.name,
						value?.type === "JSXExpressionContainer" &&
							value.expression.type !== "JSXEmptyExpression"
							? value.expression
							: value,
					);
				}
				// Only a link names `to` and `search`; without `to` an element is no navigation.
				if (names.has("to") && staysOnPage(names, spread)) {
					context.report({ node, messageId: "reset" });
				}
			},
		};
	},
});
