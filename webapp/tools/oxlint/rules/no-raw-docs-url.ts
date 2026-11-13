import { defineRule, type ESTree } from "@oxlint/plugins";

/** The public docs site's host. `src/lib/docs-url.ts` is the one module that may write it. */
const DOCS_HOST = "docs.hephaestus.build";

export const noRawDocsUrl = defineRule({
	meta: {
		type: "problem",
		docs: {
			description: `A link to the docs names an entry of \`@/lib/doc-targets\` (\`<DocLink doc="…">\` or \`docUrl("…")\`), never an address on ${DOCS_HOST}. The registry is what the docs build checks, so a renamed page fails the build instead of breaking the link. The registry entry also opens the docs of this instance's release, not always the latest ones.`,
		},
		messages: {
			raw: 'Add the page to `@/lib/doc-targets` and link it with `<DocLink doc="…">` or `docUrl("…")`, so the docs build checks it and it opens the docs of this instance\'s release.',
		},
	},
	create(context) {
		const check = (node: ESTree.Node, text: unknown) => {
			if (typeof text === "string" && text.includes(DOCS_HOST)) {
				context.report({ node, messageId: "raw" });
			}
		};
		return {
			Literal(node) {
				check(node, node.value);
			},
			TemplateElement(node) {
				check(node, node.value.cooked ?? node.value.raw);
			},
			JSXText(node) {
				check(node, node.value);
			},
		};
	},
});
