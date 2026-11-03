import { definePracticeScript, listWithin } from "@hephaestus/practice-scripts";
import { isTestPath, languageOf, scm } from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const source = [...work.changedFiles().keys()].filter((path) => languageOf(path) !== null);
		const tests = source.filter((path) => isTestPath(path));
		const production = source.filter((path) => !isTestPath(path));

		const directions: string[] = [];
		if (source.length > 0) {
			const counts = `The diff changes ${String(production.length)} production source file(s) and ${String(tests.length)} test file(s), by path`;
			directions.push(
				tests.length > 0
					? `${counts}: ${listWithin(tests, DIRECTION_CHARS - counts.length - 3)}.`
					: `${counts}.`,
				"Whether the repository already has a test target does not decide this practice: judge the changed behaviour against the tests in this diff.",
			);
		}
		return ctx.review({
			leads: [],
			facts: {
				diffProductionFiles: production.length,
				diffTestFiles: tests.length,
			},
			directions,
		});
	},
});
