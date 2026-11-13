import { defineRule } from "@oxlint/plugins";

/** React 19 renders a context as its own provider; React plans to deprecate `<Context.Provider>`. */
export const noContextProvider = defineRule({
	meta: {
		type: "suggestion",
		docs: {
			description:
				"Render a context as its own provider: `<ThemeContext value={…}>`, not `<ThemeContext.Provider value={…}>` (https://react.dev/blog/2024/12/05/react-19#context-as-a-provider).",
		},
		messages: {
			provider:
				"React 19 renders `{{context}}` as its own provider. Write `<{{context}} value={…}>` instead of `<{{context}}.Provider>`.",
		},
	},
	create(context) {
		return {
			JSXOpeningElement(node) {
				const name = node.name;
				if (
					name.type !== "JSXMemberExpression" ||
					name.property.name !== "Provider" ||
					name.object.type !== "JSXIdentifier" ||
					!name.object.name.endsWith("Context")
				) {
					return;
				}
				context.report({ node: name, messageId: "provider", data: { context: name.object.name } });
			},
		};
	},
});
