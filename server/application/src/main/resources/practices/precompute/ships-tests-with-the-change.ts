// Precompute HINTS for ships-tests-with-the-change. Surfaces facts only — the LLM judges.
import { globFilesSync } from "../lib/files.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

const TEST =
	/(?:^|\/)(?:tests?|specs?|__tests__)(?:\/)|[._-](?:test|tests|spec|specs)\.[a-z]+$|Tests?\.[a-z0-9]+$|Spec\.[a-z0-9]+$/iu;
const CODE = /\.(?:swift|ts|tsx|js|jsx|py|java|kt|go|rb|cs|cpp|cc|cxx|c|m|mm|h|hpp)$/iu;
const isTest = (p: string) => TEST.test(p);
// Mirrors lib/grep.ts#shouldIncludeDiscoveredFile: skip node_modules, .build, and dotfile dirs.
const isExcluded = (p: string) =>
	p.split("/").some((seg) => seg === "node_modules" || seg === ".build" || seg.startsWith("."));

export default function shipsTestsWithTheChange(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	let repoTestFileCount = 0;
	let repoCodeFileCount = 0;
	// One tree walk classified by extension, instead of one full scan per extension (~19x the I/O
	// under the shared 15s precompute timeout). The brace pattern enumerates the same code extensions.
	const pattern = "**/*.{swift,ts,tsx,js,jsx,py,java,kt,go,rb,cs,cpp,cc,cxx,c,m,mm,h,hpp}";
	for (const path of globFilesSync(pattern, repoPath)) {
		if (isExcluded(path)) {
			continue;
		}
		repoCodeFileCount += 1;
		if (isTest(path)) {
			repoTestFileCount += 1;
		}
	}
	const changed = [...diffFiles.keys()];
	const diffTestFiles = changed.filter(isTest).length;
	const diffProdFiles = changed.filter((f) => !isTest(f) && CODE.test(f)).length;
	// A repo with literally ZERO source files of ANY kind almost always means the worktree was not
	// readable by this script (empty/unmounted clone), NOT that the project has no code. In that case
	// repoTestFileCount=0 is UNRELIABLE and must never be read as "no tests exist".
	const worktreeVisible = repoCodeFileCount > 0;

	const directions: string[] = [];
	if (!worktreeVisible) {
		directions.push(
			"WORKTREE NOT VISIBLE to this script (0 source files of any kind were seen), so repoTestFileCount is UNRELIABLE — it is NOT evidence that tests are missing. Judge test-shipping from the DIFF instead: look for added/changed test files (+/- lines in *Test/*Spec/test_ files) and any test-run claim in the PR body; do not assert 'no tests' from this count.",
		);
	} else if (repoTestFileCount === 0) {
		directions.push(
			"No test files were found anywhere in the repository (worktree WAS readable) — there is likely no test target, so whether THIS change ships a covering test cannot be assessed here; still confirm against the diff before concluding tests are missing.",
		);
	} else if (diffProdFiles > 0 && diffTestFiles === 0) {
		directions.push(
			`The change touches ${diffProdFiles} production code file(s) and 0 test file(s) — investigate whether the added/changed behaviour has a covering test (the repo does have ${repoTestFileCount} test files, so adding one was possible).`,
		);
	} else if (diffTestFiles > 0) {
		directions.push(
			`The change adds/edits ${diffTestFiles} test file(s) alongside ${diffProdFiles} production file(s).`,
		);
	}
	return {
		hints: [],
		metrics: {
			repoTestFileCount,
			repoCodeFileCount,
			worktreeVisible: worktreeVisible ? 1 : 0,
			diffProductionFiles: diffProdFiles,
			diffTestFiles,
		},
		directions,
	};
}
