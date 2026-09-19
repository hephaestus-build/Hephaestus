// Precompute HINTS for logs-through-the-platform-logger: the print-style and logger calls ADDED in
// application code, per language, and whether the checkout already defines a logger. The closed list
// of print shapes and logger shapes per language mirrors the criteria; whether a print is the program's
// output (a CLI, a script) or a shipped debugging line is the review's to decide from the file.
import { grep } from "../lib/grep.ts";
import { scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

interface Shapes {
	print: SourcePattern[];
	logger: SourcePattern[];
}

const SCRIPTING: Shapes = {
	print: [["console.*", /\bconsole\.(?:log|debug|info|warn|error)\s*\(/]],
	logger: [["logger", /\b(?:log|logger)\.(?:trace|debug|info|warn|error|fatal)\s*\(/]],
};

// language -> the print-style shapes and the logger shapes the criteria list for it.
const DIAGNOSTICS: Record<string, Shapes> = {
	swift: {
		print: [
			["print(", /(?:^|[^\w.])print\s*\(/],
			["debugPrint(", /\bdebugPrint\s*\(/],
			["dump(", /(?:^|[^\w.])dump\s*\(/],
			["NSLog(", /\bNSLog\s*\(/],
		],
		logger: [
			[
				"Logger",
				/\bLogger\s*\(|\blogger\.(?:trace|debug|info|notice|warning|error|critical|fault|log)\s*\(/,
			],
			["os_log", /\bos_log\s*\(/],
		],
	},
	"objective-c": {
		print: [
			["NSLog(", /\bNSLog\s*\(/],
			["printf(", /\bprintf\s*\(/],
		],
		logger: [["os_log", /\bos_log\s*\(/]],
	},
	kotlin: {
		print: [
			["println(", /(?:^|[^\w.])println?\s*\(/],
			["Log with ad-hoc tag", /\bLog\.[dviwe]\s*\(\s*"/],
		],
		logger: [
			["Timber/Log with shared tag", /\bTimber\.[dviwe]\s*\(|\bLog\.[dviwe]\s*\(\s*(?:TAG|tag)\b/],
			["logger", /\blog(?:ger)?\.(?:trace|debug|info|warn|error)\s*\(/],
		],
	},
	java: {
		print: [
			["System.out/err", /\bSystem\.(?:out|err)\.print/],
			["printStackTrace()", /\.printStackTrace\s*\(/],
		],
		logger: [
			["logger", /\b(?:log|logger|LOG|LOGGER)\.(?:trace|debug|info|warn|error|severe|fine)\s*\(/],
		],
	},
	typescript: SCRIPTING,
	javascript: SCRIPTING,
	python: {
		print: [["print(", /(?:^|[^\w.])print\s*\(/]],
		logger: [
			["logging", /\b(?:logging|logger|log)\.(?:debug|info|warning|error|exception|critical)\s*\(/],
		],
	},
};

/** Where a print is the program's output rather than a diagnostic: the criteria's exemptions, by path. */
const TOOL_PATH =
	/(?:^|\/)(?:scripts?|tools?|bin|cli|Scripts|fastlane|ci)\/|\.(?:sh|mjs|cjs)$|(?:^|\/)main\.(?:py|ts|js)$|Playground|\.playground\//;

export default async function logsThroughThePlatformLogger(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	let prints = 0;
	let loggers = 0;
	let printsInToolPaths = 0;
	for (const [language, shapes] of Object.entries(DIAGNOSTICS)) {
		const scan = await scanAddedLines(repoPath, diffFiles, {
			languages: [language],
			patterns: [...shapes.print, ...shapes.logger],
			maxHints: 40,
		});
		for (const hint of scan.hints) {
			const isPrint = shapes.print.some(([label]) => label === hint.pattern);
			const tool = TOOL_PATH.test(hint.file);
			hints.push({
				...hint,
				pattern: `${language}:${hint.pattern}`,
				flags: { ...hint.flags, kind: isPrint ? "print" : "logger", toolPath: tool },
			});
			if (isPrint) {
				prints++;
				if (tool) printsInToolPaths++;
			} else loggers++;
		}
	}
	// Whether the project already logs through a logger: a definition or import in the checkout.
	const existing = await grep(
		"\\bLogger\\s*\\(|\\bos_log\\b|\\bTimber\\b|LoggerFactory|import logging|from 'pino'|from \"pino\"|winston",
		repoPath,
		{ maxResults: 5 },
	);
	const directions: string[] = [];
	if (prints > 0) {
		directions.push(
			`${prints} print-style call(s) added (${printsInToolPaths} under a tool or script path) against ${loggers} logger call(s); the checkout ${existing.length > 0 ? `already defines a logger (${existing[0]?.file ?? ""})` : "defines no logger"}. Read each print's file to decide whether it is the program's output, a DEBUG-only block or a shipped diagnostic.`,
		);
	} else if (loggers > 0) {
		directions.push(
			`${loggers} logger call(s) added and no print-style call; check the level and category on each.`,
		);
	}
	return {
		hints: hints.slice(0, 40),
		metrics: {
			printsAdded: prints,
			loggerCallsAdded: loggers,
			printsInToolPaths,
			checkoutHasLogger: existing.length > 0 ? 1 : 0,
		},
		directions,
	};
}
