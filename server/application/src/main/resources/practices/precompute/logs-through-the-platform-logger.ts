// Precompute HINTS for logs-through-the-platform-logger: the print-style and logger calls ADDED in
// application code, per language, and whether a bounded search of the checkout matches a logger. The
// closed list of print shapes and logger shapes per language mirrors the criteria; whether a print is the
// program's output (a CLI, a script) or a shipped debugging line is the review's to decide from the file.
import { grep } from "../lib/grep.ts";
import { countLabel, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

interface Shapes {
	print: SourcePattern[];
	logger: SourcePattern[];
}

const SCRIPTING: Shapes = {
	print: [["console.*", /\bconsole\.(?:log|debug|info|warn|error)\s*\(/u]],
	logger: [["logger", /\b(?:log|logger)\.(?:trace|debug|info|warn|error|fatal)\s*\(/u]],
};

// language -> the print-style shapes and the logger shapes the criteria list for it.
const DIAGNOSTICS: Record<string, Shapes> = {
	swift: {
		print: [
			["print(", /(?:^|[^\w.])print\s*\(/u],
			["debugPrint(", /\bdebugPrint\s*\(/u],
			["dump(", /(?:^|[^\w.])dump\s*\(/u],
			["NSLog(", /\bNSLog\s*\(/u],
		],
		logger: [
			[
				"Logger",
				/\bLogger\s*\(|\b(?:logger|Logger\.shared)\.(?:trace|debug|info|notice|warning|error|critical|fault|log)\s*\(/u,
			],
			["os_log", /\bos_log\s*\(/u],
		],
	},
	"objective-c": {
		print: [
			["NSLog(", /\bNSLog\s*\(/u],
			["printf(", /\bprintf\s*\(/u],
		],
		logger: [["os_log", /\bos_log\s*\(/u]],
	},
	kotlin: {
		print: [
			["println(", /(?:^|[^\w.])println?\s*\(/u],
			["Log with ad-hoc tag", /\bLog\.[dviwe]\s*\(\s*"/u],
		],
		logger: [
			["Timber/Log with shared tag", /\bTimber\.[dviwe]\s*\(|\bLog\.[dviwe]\s*\(\s*(?:TAG|tag)\b/u],
			["logger", /\blog(?:ger)?\.(?:trace|debug|info|warn|error)\s*\(/u],
		],
	},
	java: {
		print: [
			["System.out/err", /\bSystem\.(?:out|err)\.print/u],
			["printStackTrace()", /\.printStackTrace\s*\(/u],
		],
		logger: [
			["logger", /\b(?:log|logger|LOG|LOGGER)\.(?:trace|debug|info|warn|error|severe|fine)\s*\(/u],
		],
	},
	typescript: SCRIPTING,
	javascript: SCRIPTING,
	python: {
		print: [["print(", /(?:^|[^\w.])print\s*\(/u]],
		logger: [
			[
				"logging",
				/\b(?:logging|logger|log)\.(?:debug|info|warning|error|exception|critical)\s*\(/u,
			],
		],
	},
};

/** Where a print is the program's output rather than a diagnostic: the criteria's exemptions, by path. */
const TOOL_PATH =
	/(?:^|\/)(?:scripts?|tools?|bin|cli|Scripts|fastlane|ci)\/|\.(?:sh|mjs|cjs)$|(?:^|\/)main\.(?:py|ts|js)$|Playground|\.playground\//u;

export default async function logsThroughThePlatformLogger(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const hints: Hint[] = [];
	let prints = 0;
	let loggers = 0;
	let filesScanned = 0;
	let linesAdded = 0;
	for (const [language, shapes] of Object.entries(DIAGNOSTICS)) {
		const scan = await scanAddedLines(repoPath, diffFiles, {
			languages: [language],
			patterns: [...shapes.print, ...shapes.logger],
			maxHints: 40,
		});
		filesScanned += scan.filesScanned;
		linesAdded += scan.linesAdded;
		prints += shapes.print.reduce((sum, [label]) => sum + countLabel(scan, label), 0);
		loggers += shapes.logger.reduce((sum, [label]) => sum + countLabel(scan, label), 0);
		for (const hint of scan.hints) {
			const isPrint = shapes.print.some(([label]) => label === hint.pattern);
			hints.push({
				...hint,
				pattern: `${language}:${hint.pattern}`,
				flags: {
					...hint.flags,
					kind: isPrint ? "print" : "logger",
					toolPath: TOOL_PATH.test(hint.file),
				},
			});
		}
	}
	const listed = hints.slice(0, 40);
	const printsInToolPaths = listed.filter(
		(h) => h.flags.kind === "print" && h.flags.toolPath === true,
	).length;
	// Whether a bounded lexical search of the checkout matches a logger definition or import.
	const existing = await grep(
		String.raw`\bLogger\s*\(|\bos_log\b|\bTimber\b|LoggerFactory|import logging|from 'pino'|from "pino"|winston`,
		repoPath,
		{ maxResults: 5 },
	);
	const directions: string[] =
		prints + loggers > listed.length
			? [
					`${String(prints + loggers)} diagnostic line(s) added; ${String(listed.length)} are listed. The counts by kind cover every line; the tool-path count covers only the listed rows.`,
				]
			: [];
	if (prints > 0) {
		directions.push(
			`${prints} print-style call(s) added (${printsInToolPaths} listed under a tool or script path) against ${loggers} logger call(s); ${existing.length > 0 ? `the checkout matches a logger (${existing[0]?.file ?? ""})` : "a bounded search of the checkout matched no logger definition, which does not show there is none"}. Read each print's file to decide whether it is the program's output, a DEBUG-only block or a shipped diagnostic.`,
		);
	} else if (loggers > 0) {
		directions.push(`${loggers} logger call(s) added and no print-style call.`);
	}
	return {
		hints: listed,
		metrics: {
			printsAdded: prints,
			loggerCallsAdded: loggers,
			printsInToolPaths,
			checkoutHasLogger: existing.length > 0 ? 1 : 0,
			filesScanned,
			linesAdded,
		},
		directions,
	};
}
