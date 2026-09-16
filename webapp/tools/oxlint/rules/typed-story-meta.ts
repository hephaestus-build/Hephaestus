import { defineRule, type ESTree } from "@oxlint/plugins";

import { propertyName } from "../property.ts";

/**
 * What the rule can see, and its ceiling. A JS plugin gets the syntax tree and no type information
 * (https://oxc.rs/docs/guide/usage/linter/js-plugins), so it checks that a `Meta` names *a*
 * component, never that it names the right one: `satisfies Meta<typeof SomeOtherThing>` passes here
 * and only `tsc` catches it. For the same reason the named form is recognised by the identifier
 * `meta`, which is the CSF convention rather than anything the tree states.
 */
export const typedStoryMeta = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A story `meta` naming a `component` is checked against it, by `satisfies Meta<typeof That>`. A gallery meta that names no component is the one case a bare `Meta` is right.",
		},
		messages: {
			untyped:
				"This `meta` names a `component` but is typed as a bare `Meta`, so its `args` are never checked against that component's props and Storybook's generated controls drift with them. Type it `Meta<typeof TheComponent>`.",
			unchecked:
				"This `meta` carries no type, so nothing checks its `args` against the component's props and `StoryObj<typeof meta>` infers from an object nobody constrained. End it `satisfies Meta<typeof TheComponent>`.",
			asserted:
				"`as` asserts where `satisfies` checks: an object asserted `as Meta<typeof X>` may carry an `arg` the component has no prop for, or omit one it requires. Write `satisfies Meta<typeof X>`.",
		},
	},
	create(context) {
		/** `Meta` and `SB.Meta` name the same type; a namespace import changes the spelling only. */
		const asMetaReference = (type: ESTree.TSType | null | undefined) => {
			if (type?.type !== "TSTypeReference") {
				return;
			}
			const { typeName } = type;
			if (typeName.type === "Identifier" && typeName.name === "Meta") {
				return type;
			}
			if (typeName.type === "TSQualifiedName" && typeName.right.name === "Meta") {
				return type;
			}
			return;
		};

		const namesComponent = (meta: ESTree.Node) =>
			meta.type === "ObjectExpression" &&
			meta.properties.some(
				(property) => property.type === "Property" && propertyName(property) === "component",
			);

		/** A stated `Meta` with no type argument pins nothing about the component it names. */
		const checkStatedType = (
			meta: ESTree.TSTypeReference,
			value: ESTree.Expression | null | undefined,
		) => {
			if (meta.typeArguments) {
				return;
			}
			if (value && namesComponent(value)) {
				context.report({ node: value, messageId: "untyped" });
			}
		};

		return {
			TSSatisfiesExpression(node) {
				const meta = asMetaReference(node.typeAnnotation);
				if (meta) {
					checkStatedType(meta, node.expression);
				}
			},
			TSAsExpression(node) {
				const meta = asMetaReference(node.typeAnnotation);
				if (meta) {
					context.report({ node: meta, messageId: "asserted" });
				}
			},
			VariableDeclarator(node) {
				const annotation = asMetaReference(node.id.typeAnnotation?.typeAnnotation);
				if (annotation) {
					checkStatedType(annotation, node.init);
					return;
				}
				// `node.id.typeAnnotation` rather than `annotation`: `const meta: StoryObj = …` states a
				// type, just not a `Meta`, and what that type checks is its own question.
				if (node.id.typeAnnotation || node.id.type !== "Identifier" || node.id.name !== "meta") {
					return;
				}
				if (node.init && namesComponent(node.init)) {
					context.report({ node: node.init, messageId: "unchecked" });
				}
			},
			// CSF3 lets the meta leave as the default export directly, under no name to recognise it by.
			ExportDefaultDeclaration(node) {
				if (namesComponent(node.declaration)) {
					context.report({ node: node.declaration, messageId: "unchecked" });
				}
			},
		};
	},
});
