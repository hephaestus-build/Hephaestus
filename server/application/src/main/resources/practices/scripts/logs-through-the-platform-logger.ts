import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

interface Shapes {
	print: [string, RegExp][];
	logger: [string, RegExp][];
}

const SCRIPTING: Shapes = {
	print: [["console.*", /\bconsole\.(?:log|debug|info|warn|error)\s*\(/u]],
	logger: [["logger", /\b(?:log|logger)\.(?:trace|debug|info|warn|error|fatal)\s*\(/u]],
};

const APPLE_SYSTEM_LOG: [string, RegExp][] = [
	["os_log", /\bos_log(?:_(?:info|debug|error|fault))?\s*\(/u],
	["NSLog", /\bNSLogv?\s*\(/u],
];

// A bare Log name does not establish android.util.Log; the review must resolve its import.
const ANDROID_LOG: [string, RegExp][] = [
	["android.util.Log", /\b(?:android\.util\.)?Log\.(?:v|d|i|w|e|wtf)\s*\(/u],
	["Timber", /\bTimber\.(?:v|d|i|w|e|wtf)\s*\(/u],
];

const DIAGNOSTICS: Record<string, Shapes> = {
	swift: {
		print: [
			["print(", /(?:^|[^\w.])print\s*\(/u],
			["debugPrint(", /\bdebugPrint\s*\(/u],
			["dump(", /(?:^|[^\w.])dump\s*\(/u],
		],
		logger: [
			[
				"Logger",
				/\bLogger\s*\(|\b(?:logger|Logger\.shared)\.(?:trace|debug|info|notice|warning|error|critical|fault|log)\s*\(/u,
			],
			...APPLE_SYSTEM_LOG,
		],
	},
	"objective-c": {
		print: [["printf(", /\bprintf\s*\(/u]],
		logger: APPLE_SYSTEM_LOG,
	},
	kotlin: {
		print: [["println(", /(?:^|[^\w.])println?\s*\(/u]],
		logger: [...ANDROID_LOG, ["logger", /\blog(?:ger)?\.(?:trace|debug|info|warn|error)\s*\(/u]],
	},
	java: {
		print: [
			["System.out/err", /\bSystem\.(?:out|err)\.print/u],
			["printStackTrace()", /\.printStackTrace\s*\(/u],
		],
		logger: [
			...ANDROID_LOG,
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

const TOOL_PATH =
	/(?:^|\/)(?:scripts?|tools?|bin|cli|Scripts|fastlane|ci)\/|\.(?:sh|mjs|cjs)$|(?:^|\/)main\.(?:py|ts|js)$|Playground|\.playground\//u;

const LISTED = 40;

export const LABELS = {
	"print-call": "A selected print-style call, such as print, println, console.log or System.out.",
	"logger-call":
		"A selected call to a logger, such as Logger, os_log, NSLog, android.util.Log or logging.",
};

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const tree = work.head;
		const leads: ReviewLead[] = [];
		let prints = 0;
		let loggers = 0;
		let filesScanned = 0;
		let linesSelected = 0;
		for (const [language, shapes] of Object.entries(DIAGNOSTICS)) {
			const rows = [...shapes.print, ...shapes.logger].map(([kind, test]) => ({
				kind,
				label: kind,
				test,
			}));
			const found = await work.sites(
				{ [language]: rows },
				{ overlap: "first", enclosing: {}, listed: LISTED },
			);
			filesScanned += found.filesScanned;
			linesSelected += found.linesSelected;
			const count = (kinds: readonly [string, RegExp][]) =>
				kinds.reduce((sum, [kind]) => sum + (found.counts.get(kind) ?? 0), 0);
			prints += count(shapes.print);
			loggers += count(shapes.logger);
			for (const site of found.listed) {
				const isPrint = shapes.print.some(([kind]) => kind === site.kind);
				leads.push({
					at: { file: site.path, line: site.line, endLine: site.endLine },
					label: isPrint ? LABELS["print-call"] : LABELS["logger-call"],
					facts: {
						language,
						call: site.kind,
						...site.facts,
						toolPath: TOOL_PATH.test(site.path),
					},
				});
			}
		}
		const listed = leads.slice(0, LISTED);
		const printsInToolPaths = listed.filter(
			(lead) => lead.label === LABELS["print-call"] && lead.facts?.toolPath === true,
		).length;
		const { matches: existing } =
			tree === null
				? { matches: [] }
				: await tree.grep(
						String.raw`\bLogger\s*\(|\bos_log|\bNSLogv?\s*\(|import android\.util\.Log\b|\bTimber\b|LoggerFactory|import logging|from 'pino'|from "pino"|winston`,
						{ maxResults: 5 },
					);
		const directions: string[] =
			prints + loggers > listed.length
				? [
						`${String(prints + loggers)} diagnostic line(s) selected; ${String(listed.length)} are listed. The counts by kind cover every line; the tool-path count covers only the listed rows.`,
					]
				: [];
		if (prints > 0) {
			directions.push(
				`${String(prints)} print-style call(s) selected (${String(printsInToolPaths)} listed under a tool or script path) against ${String(loggers)} logger call(s); ${existing.length > 0 ? `the checkout matches a logger (${existing[0]?.file ?? ""})` : "a bounded search of the checkout matched no logger definition, which does not show there is none"}.`,
				"Read each print's file to decide whether it is the program's output, a DEBUG-only block or a shipped diagnostic.",
			);
		} else if (loggers > 0) {
			directions.push(`${String(loggers)} logger call(s) selected and no print-style call.`);
		}
		if (listed.length === 0) {
			directions.push(
				`Scanned ${String(linesSelected)} added lines in ${String(filesScanned)} files for this practice's line patterns; none matched.`,
				"A pattern sees one line: what spans lines or has no keyword, such as an early return, a discarded result or a missing else, is yours to enumerate from the diff.",
			);
		}
		return ctx.review({
			leads: listed,
			facts: {
				printsSelected: prints,
				loggerCallsSelected: loggers,
				printsInToolPaths,
				checkoutHasLogger: existing.length > 0 ? 1 : 0,
				filesScanned,
				linesSelected,
			},
			directions,
		});
	},
});
