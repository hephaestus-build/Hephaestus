import { defineRule, type ESTree } from "@oxlint/plugins";

// These keys are constructed by the client from local choices or explicit branches, not wire enums.
const CLIENT_DEFINITIONS = new Set([
	"ACTIVITY_CATEGORY_DEFS",
	"ACTIVITY_RANGE_DEFS",
	"REVIEW_RANGE_DEFS",
	"REVIEW_TIMEFRAME_DEFS",
	"WINDOW_DEFS",
	"BUCKET_SIZE_DEFS",
	"OPEN_WORK_GROUP_DEFS",
	"FEEDBACK_STATE_DEFS",
	"REVIEW_READINESS_DEFS",
	"MEMBER_PUBLIC_ACTIVITY_DEFS",
	"BINDING_READINESS_DEFS",
	"MODEL_READINESS_DEFS",
	"PURPOSE_STATUS_DEFS",
	"WORKSPACE_SETUP_STATUS_DEFS",
	"PRACTICE_MARKER_DEFS",
	"PRACTICE_SCRIPT_GUIDE_DEFS",
	"DEVELOPER_ANSWER_DEFS",
	"DELIVERY_FAMILY_DEFS",
	"QUEUE_ITEM_DEFS",
	"PURSE_DEFS",
	"CAP_STATE_DEFS",
]);

// Audited finite option lists. An arbitrary uppercase identifier is not proof of a local key.
const LOCAL_CHOICES = new Set([
	"AI_CHOICES",
	"OPEN_WORK_GROUPS",
	"RESOLVING_ANSWERS",
	"REVIEW_TIMEFRAMES",
	"WINDOW_VALUES",
	"PROBLEM_BASELINE_STATES",
	"SCAN_FILE_FILTERS",
	"FEEDBACK_MODE_ORDER",
	"REVIEW_RANGES",
	"MARK_FILTERS",
]);

function isLocalChoice(node: ESTree.MemberExpression): boolean {
	if (node.property.type !== "Identifier") {
		return false;
	}
	const key = node.property.name;
	let parent: ESTree.Node | null = node.parent;
	while (parent.type !== "Program") {
		if (
			parent.type === "ArrowFunctionExpression" &&
			parent.params.some(
				(param) =>
					(param.type === "Identifier" && param.name === key) ||
					(param.type === "ObjectPattern" &&
						param.properties.some(
							(property) =>
								property.type === "Property" &&
								property.value.type === "Identifier" &&
								property.value.name === key,
						)),
			)
		) {
			const call = parent.parent;
			if (call.type !== "CallExpression" || call.callee.type !== "MemberExpression") {
				return false;
			}
			let choices: ESTree.Node = call.callee.object;
			while (choices.type === "TSAsExpression" || choices.type === "TSSatisfiesExpression") {
				choices = choices.expression;
			}
			// Filtering retains the registry's finite keys.
			if (
				choices.type === "CallExpression" &&
				choices.callee.type === "MemberExpression" &&
				!choices.callee.computed &&
				choices.callee.property.type === "Identifier" &&
				choices.callee.property.name === "filter"
			) {
				choices = choices.callee.object;
			}
			return (
				(choices.type === "ArrayExpression" &&
					choices.elements.every((element) => element?.type === "Literal")) ||
				(choices.type === "Identifier" && LOCAL_CHOICES.has(choices.name)) ||
				(choices.type === "CallExpression" &&
					choices.callee.type === "Identifier" &&
					choices.callee.name === "statusValues" &&
					choices.arguments[0]?.type === "Identifier" &&
					node.object.type === "Identifier" &&
					choices.arguments[0].name === node.object.name)
			);
		}
		parent = parent.parent;
	}
	return false;
}

export const noUnsafeDefinitionLookup = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A server enum can be newer than this build. Use definitionFor so an unknown value has a neutral display.",
		},
		messages: {
			unsafe:
				"Use definitionFor(registry, value), with a typed fallback for extra fields. A newer server can send an unknown value.",
		},
	},
	create(context) {
		if (
			/\.(?:test|stories)\.[cm]?[jt]sx?$|\/(?:fixtures|lineage-story|tour-scenes)\.tsx?$|\/stories\//u.test(
				context.filename.replaceAll("\\", "/"),
			)
		) {
			return {};
		}
		return {
			MemberExpression(node) {
				if (
					!node.computed ||
					node.object.type !== "Identifier" ||
					!node.object.name.endsWith("_DEFS") ||
					CLIENT_DEFINITIONS.has(node.object.name)
				) {
					return;
				}
				if (
					node.property.type === "Literal" ||
					(node.property.type === "TemplateLiteral" && node.property.expressions.length === 0) ||
					isLocalChoice(node)
				) {
					return;
				}
				context.report({ node, messageId: "unsafe" });
			},
		};
	},
});
