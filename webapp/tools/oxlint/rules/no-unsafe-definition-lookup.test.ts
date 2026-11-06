import { ruleTester } from "../rule-tester.ts";
import { noUnsafeDefinitionLookup } from "./no-unsafe-definition-lookup.ts";

ruleTester.run("no-unsafe-definition-lookup", noUnsafeDefinitionLookup, {
	valid: [
		"definitionFor(OUTCOME_DEFS, result.outcome).label;",
		"OUTCOME_DEFS.MET.label;",
		'OUTCOME_DEFS["MET"].label;',
		"statusValues(OUTCOME_DEFS).map(value => OUTCOME_DEFS[value].label);",
		"statusValues(OUTCOME_DEFS).filter(isShown).map(value => OUTCOME_DEFS[value].label);",
		"FEEDBACK_MODE_ORDER.map(value => FEEDBACK_MODE_DEFS[value].label);",
		'(["MET"] as const).map(value => OUTCOME_DEFS[value].label);',
		"MARK_FILTERS.map(({kind}) => RESULT_MARK_DEFS[kind].label);",
		"ACTIVITY_CATEGORY_DEFS[category].label(provider);",
		{ code: "OUTCOME_DEFS[result.outcome].label;", filename: "result.stories.tsx" },
		...[
			"src/components/admin/practice-reviews/fixtures.ts",
			"src/components/admin/practice-reviews/lineage-story.ts",
			"src/components/tour/tour-scenes.tsx",
			"src/stories/results.ts",
		].flatMap((filename) =>
			[filename, filename.replaceAll("/", "\\")].map((path) => ({
				code: "OUTCOME_DEFS[result.outcome].label;",
				filename: path,
			})),
		),
	],
	invalid: [
		{ code: "OUTCOME_DEFS[result.outcome].label;", errors: [{ messageId: "unsafe" }] },
		{ code: "OUTCOME_DEFS[outcome];", errors: [{ messageId: "unsafe" }] },
		{
			code: "OUTCOME_DEFS[result.outcome].label;",
			filename: String.raw`src\components\results.tsx`,
			errors: [{ messageId: "unsafe" }],
		},
		{ code: "rows.map(row => OUTCOME_DEFS[row.outcome]);", errors: [{ messageId: "unsafe" }] },
		{ code: "serverValues.map(value => OUTCOME_DEFS[value]);", errors: [{ messageId: "unsafe" }] },
		{ code: "SERVER_VALUES.map(value => OUTCOME_DEFS[value]);", errors: [{ messageId: "unsafe" }] },
		{
			code: "statusValues(OTHER_DEFS).map(value => OUTCOME_DEFS[value]);",
			errors: [{ messageId: "unsafe" }],
		},
		{ code: "[serverValue].map(value => OUTCOME_DEFS[value]);", errors: [{ messageId: "unsafe" }] },
		{ code: "OUTCOME_DEFS[key + suffix];", errors: [{ messageId: "unsafe" }] },
		{
			code: "serverValues.filter(isShown).map(value => OUTCOME_DEFS[value]);",
			errors: [{ messageId: "unsafe" }],
		},
		{
			code: "statusValues(OUTCOME_DEFS).map(() => serverValue).filter(isShown).map(value => OUTCOME_DEFS[value]);",
			errors: [{ messageId: "unsafe" }],
		},
	],
});
