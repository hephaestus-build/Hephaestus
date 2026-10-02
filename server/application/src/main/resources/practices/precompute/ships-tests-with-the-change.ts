// Precompute FACTS for ships-tests-with-the-change: which changed source files are tests and which are
// production code, by path convention. Whether a test covers the changed behaviour is the review's to
// read from the diff.
import { isTestPath, languageOf } from "../lib/languages.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default function shipsTestsWithTheChange(
	_repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	const source = [...diffFiles.keys()].filter((path) => languageOf(path) !== null);
	const tests = source.filter((path) => isTestPath(path));
	const production = source.filter((path) => !isTestPath(path));

	const directions: string[] = [];
	if (source.length > 0) {
		directions.push(
			`The diff changes ${String(production.length)} production source file(s) and ${String(tests.length)} test file(s), by path${tests.length > 0 ? `: ${tests.slice(0, 10).join(", ")}` : ""}. Whether the repository already has a test target does not decide this practice: judge the changed behaviour against the tests in this diff.`,
		);
	}
	return {
		hints: [],
		metrics: {
			diffProductionFiles: production.length,
			diffTestFiles: tests.length,
		},
		directions,
	};
}
