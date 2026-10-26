import { scm, expectKind, requireChange } from "../kits/scm/index.ts";
import { definePracticeScript } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const domain = scm(ctx);
		expectKind(domain, "scm.pull_request");
		const changed = requireChange(domain.files).size;
		await ctx.evidence.record("metadata.json");
		const citation = await ctx.evidence.citeRecord("metadata.json", 36);
		return {
			outcome: "DOES_NOT_APPLY",
			summary: "The change adds or changes no user interface",
			explanation: `None of the ${changed} changed files is a view, a template or a stylesheet.`,
			citations: [citation],
			evidence: {
				inapplicability: {
					consulted: ["scm.pull-request.core", "scm.pull-request.diff"],
					prerequisite: "user interface files that the change adds or changes",
					ruledOutBy: "Every changed file is a TypeScript source outside a view folder.",
				},
			},
		};
	},
});
