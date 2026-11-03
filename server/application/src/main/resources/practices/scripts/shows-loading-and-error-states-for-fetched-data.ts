import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, scm } from "@hephaestus/practice-scripts/scm";

const NETWORKING =
	/URLSession|\.data\(from|\.dataTask|\.request\(|Alamofire|AF\.|Data\(\s*contentsOf\s*:/u;
const ASYNC_BLOCK = /\.(?:task|onAppear)\s*\{/u;
const LOADING =
	/ProgressView|\.redacted\(|shimmer|skeleton|isLoading|showLoading|LoadingState\.loading|\.loading\b/u;
const ERROR =
	/errorMessage|errorText|showError|isError|hasError|\.alert\s*\(.*(?:error|Error|fail)|ContentUnavailableView|LoadingState\.failed|\.failed\b|alertError|displayError/u;
const MEANINGFUL_AWAIT = /await\s+(?!Task\.sleep)(?!withAnimation)\w/u;
const FETCH_CALL =
	/await\s+(?:\w+\.)*(?:fetch|load|get|retrieve|request|download|search|query|refresh|sync|update|pull)\w*\s*\(/iu;
const MODEL_CALL =
	/await\s+(?:viewModel|vm|model|manager|service|store|repository|api|client|provider)\.\w+/iu;

/** Why the view loads data, and the first line that shows it, or null. */
function dataSource(
	text: string,
	lines: readonly string[],
): { reason: string; line: number } | null {
	const lineOf = (pattern: RegExp) => lines.findIndex((line) => pattern.test(line)) + 1 || 1;
	if (/URLSession|\.data\(from|\.dataTask/u.test(text)) {
		return { reason: "URLSession", line: lineOf(/URLSession|\.data\(from/u) };
	}
	if (/Alamofire|AF\./u.test(text)) {
		return { reason: "Alamofire", line: lineOf(/Alamofire|AF\./u) };
	}
	if (
		/Data\(\s*contentsOf\s*:/u.test(text) &&
		!/Data\(\s*contentsOf\s*:\s*Bundle\.main/u.test(text)
	) {
		return { reason: "Data(contentsOf:)", line: lineOf(/Data\(\s*contentsOf/u) };
	}
	if (ASYNC_BLOCK.test(text) && MEANINGFUL_AWAIT.test(text)) {
		if (FETCH_CALL.test(text)) {
			return { reason: "async fetch in .task", line: lineOf(ASYNC_BLOCK) };
		}
		if (MODEL_CALL.test(text)) {
			return { reason: "async model call", line: lineOf(MODEL_CALL) };
		}
	}
	return null;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => file.language === "swift" && !isTestPath(file.path));
		const leads: ReviewLead[] = [];
		const facts = {
			fetchingViews: 0,
			withoutLoadingOrError: 0,
			withoutLoading: 0,
			withoutError: 0,
			spinnerWithoutError: 0,
			rawErrorText: 0,
		};
		for (const file of files) {
			const { text } = file;
			if (!/:\s*View\s*\{/u.test(text) || /import\s+XCTest|import\s+Testing/u.test(text)) {
				continue;
			}
			if (
				/@Query|@AppStorage/u.test(text) &&
				!NETWORKING.test(text) &&
				!MEANINGFUL_AWAIT.test(text)
			) {
				continue;
			}
			const lines = text.split("\n");
			const source = dataSource(text, lines);
			if (source === null) {
				continue;
			}
			facts.fetchingViews += 1;
			const hasLoading = LOADING.test(text);
			const hasError = ERROR.test(text);
			const spinner = /ProgressView/u.test(text);
			facts.withoutLoadingOrError += !hasLoading && !hasError ? 1 : 0;
			facts.withoutLoading += !hasLoading && hasError ? 1 : 0;
			facts.withoutError += hasLoading && !hasError ? 1 : 0;
			facts.spinnerWithoutError += spinner && !hasError ? 1 : 0;
			if (!hasLoading || !hasError) {
				leads.push({
					at: { file: file.path, line: source.line },
					label: "A changed view that loads data, without a loading or an error state in its file.",
					facts: {
						source: source.reason,
						hasLoadingState: hasLoading,
						hasErrorState: hasError,
						hasProgressView: spinner,
					},
				});
			}
			if (
				/Text\(.*\.localizedDescription/u.test(text) &&
				!/\/\/.*localizedDescription/u.test(text)
			) {
				facts.rawErrorText += 1;
				leads.push({
					at: {
						file: file.path,
						line:
							lines.findIndex((line) => /\.localizedDescription/u.test(line)) + 1 || source.line,
					},
					label: "A changed view that shows an error's localizedDescription as text.",
				});
			}
		}
		const directions = [
			"Leads are changed SwiftUI view files that fetch data, by URLSession, Alamofire, a remote Data(contentsOf:) or an awaited fetch in .task or .onAppear. Loading and error states are matched by name anywhere in the file, such as ProgressView, isLoading, errorMessage or ContentUnavailableView.",
			"A state may live in a view model or a parent. Read whether the user sees progress while the data loads and a message with a way forward when it fails; a spinner with no error path can spin forever.",
		];
		if (facts.rawErrorText > 0) {
			directions.push(
				"localizedDescription is written for developers and can be technical. Read whether the text shown is one a user can act on.",
			);
		}
		return ctx.review({ leads, facts, directions });
	},
});
