import { defineRule, type ESTree } from "@oxlint/plugins";

import { propertyName } from "../property.ts";

/**
 * What the rule can see, and its ceiling. A JS plugin gets the syntax tree and no type information
 * (https://oxc.rs/docs/guide/usage/linter/js-plugins), so it checks that a `Meta` names *a*
 * component, never that it names the right one: `satisfies Meta<typeof SomeOtherThing>` passes here
 * and only `tsc` catches it. For the same reason the named form is recognised by the identifier
 * `meta`, which is the CSF convention rather than anything the tree states.
 */
/** `Meta` and `SB.Meta` name the same type; a namespace import changes the spelling only. */
function asMetaReference(type: ESTree.TSType | null | undefined) {
	if (type?.type !== "TSTypeReference") {
		return;
	}
	const { typeName } = type;
	const named =
		(typeName.type === "Identifier" && typeName.name === "Meta") ||
		(typeName.type === "TSQualifiedName" && typeName.right.name === "Meta");
	return named ? type : undefined;
}

function namesComponent(meta: ESTree.Node) {
	return (
		meta.type === "ObjectExpression" &&
		meta.properties.some(
			(property) => property.type === "Property" && propertyName(property) === "component",
		)
	);
}

export const typedStoryMeta = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A story `meta` naming a `component` is checked against it, by `satisfies Meta<typeof That>`, and its stories are `StoryObj<typeof meta>`. A gallery meta that names no component is the one case a bare `Meta` is right.",
		},
		messages: {
			untyped:
				"This `meta` names a `component` but is typed as a bare `Meta`, so its `args` are never checked against that component's props and Storybook's generated controls drift with them. Type it `Meta<typeof TheComponent>`.",
			unchecked:
				"This `meta` carries no type, so nothing checks its `args` against the component's props and `StoryObj<typeof meta>` infers from an object nobody constrained. End it `satisfies Meta<typeof TheComponent>`.",
			asserted:
				"`as` asserts where `satisfies` checks: an object asserted `as Meta<typeof X>` may carry an `arg` the component has no prop for, or omit one it requires. Write `satisfies Meta<typeof X>`.",
			annotated:
				"`const meta: Meta<typeof X>` widens `typeof meta` to `Meta`, so `StoryObj<typeof meta>` no longer sees which args this meta supplies and a story that omits a required prop type-checks. Write `const meta = { … } satisfies Meta<typeof X>`.",
			storyOfComponent:
				"`StoryObj<typeof X>` makes every arg optional, so a story that omits a required prop type-checks. Write `StoryObj<typeof meta>`: it subtracts the args the meta supplies and requires the rest.",
		},
	},
	create(context) {
		return {
			/** A stated `Meta` with no type argument pins nothing about the component it names. */
			TSSatisfiesExpression(node) {
				const meta = asMetaReference(node.typeAnnotation);
				if (meta && !meta.typeArguments && namesComponent(node.expression)) {
					context.report({ node: node.expression, messageId: "untyped" });
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
					if (node.init && namesComponent(node.init)) {
						context.report({ node: annotation, messageId: "annotated" });
					}
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
			// `type Story = StoryObj<typeof meta>` is the CSF convention; any other argument loses the
			// subtraction of the meta's args that makes a missing required prop a type error.
			TSTypeAliasDeclaration(node) {
				const { typeAnnotation } = node;
				if (typeAnnotation.type !== "TSTypeReference") {
					return;
				}
				const { typeName } = typeAnnotation;
				const isStoryObj =
					(typeName.type === "Identifier" && typeName.name === "StoryObj") ||
					(typeName.type === "TSQualifiedName" && typeName.right.name === "StoryObj");
				const [argument] = typeAnnotation.typeArguments?.params ?? [];
				if (!isStoryObj || argument === undefined) {
					return;
				}
				const ofMeta =
					argument.type === "TSTypeQuery" &&
					argument.exprName.type === "Identifier" &&
					argument.exprName.name === "meta";
				if (!ofMeta) {
					context.report({ node: argument, messageId: "storyOfComponent" });
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
