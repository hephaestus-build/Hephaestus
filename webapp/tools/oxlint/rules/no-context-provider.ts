import { defineRule, type ESTree } from "@oxlint/plugins";

/** `createContext(…)` or `React.createContext(…)`. */
const isCreateContext = (node: ESTree.Node | null | undefined): boolean => {
	if (!node || node.type !== "CallExpression") {
		return false;
	}
	const callee = node.callee;
	return (
		(callee.type === "Identifier" && callee.name === "createContext") ||
		(callee.type === "MemberExpression" &&
			callee.property.type === "Identifier" &&
			callee.property.name === "createContext")
	);
};

/**
 * React 19 renders a context as its own provider; React plans to deprecate `<Context.Provider>`.
 * A context is a name that this file binds to `createContext(…)`, or an imported name that ends in
 * `Context`. Without types, the rule cannot tell an imported context with another name from a
 * namespace such as `TanstackQuery` or `Tooltip`, whose `Provider` is a component.
 */
export const noContextProvider = defineRule({
	meta: {
		type: "suggestion",
		docs: {
			description:
				"Render a context as its own provider: `<ThemeContext value={…}>`, not `<ThemeContext.Provider value={…}>` (https://react.dev/blog/2024/12/05/react-19#context-as-a-provider). Name a context so it ends in `Context`, so the rule also sees it where it is imported.",
		},
		messages: {
			provider:
				"React 19 renders `{{context}}` as its own provider. Write `<{{context}} value={…}>` instead of `<{{context}}.Provider>`.",
		},
	},
	create(context) {
		const contexts = new Set<string>();
		const providers: ESTree.JSXMemberExpression[] = [];
		return {
			VariableDeclarator(node) {
				if (node.id.type === "Identifier" && isCreateContext(node.init)) {
					contexts.add(node.id.name);
				}
			},
			JSXOpeningElement(node) {
				const name = node.name;
				if (
					name.type === "JSXMemberExpression" &&
					name.property.name === "Provider" &&
					name.object.type === "JSXIdentifier"
				) {
					providers.push(name);
				}
			},
			"Program:exit"() {
				for (const name of providers) {
					const object = name.object;
					if (object.type !== "JSXIdentifier") {
						continue;
					}
					if (object.name.endsWith("Context") || contexts.has(object.name)) {
						context.report({ node: name, messageId: "provider", data: { context: object.name } });
					}
				}
			},
		};
	},
});
