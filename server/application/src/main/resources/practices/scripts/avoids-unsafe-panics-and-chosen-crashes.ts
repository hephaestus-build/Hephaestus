import { definePracticeScript, listWithin, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, scm } from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

export const LABELS = {
	"crash-operator":
		"A selected line that spells a crash operator; its facts name the language, the operators, and if it runs in a preview block or a test file.",
};

const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		["try!", /\btry!/u],
		["fatalError", /\bfatalError\s*\(/u],
		["force cast as!", /\bas!\s/u],
		[
			"precondition or assert",
			/\b(?:precondition|preconditionFailure|assert|assertionFailure)\s*\(/u,
		],
		["exit or abort", /\b(?:exit|abort)\s*\(/u],
		["force unwrap", /[A-Za-z0-9_)\]](?<!\btry|\bas)!(?:[.\s),\]]|$)/u],
	],
	typescript: [
		["process.exit", /\bprocess\.exit\s*\(/u],
		["non-null assertion", /[A-Za-z0-9_)\]]!(?:[.;)\s,\]]|$)/u],
	],
	javascript: [["process.exit", /\bprocess\.exit\s*\(/u]],
	python: [
		["sys.exit", /\bsys\.exit\s*\(/u],
		["os._exit", /\bos\._exit\s*\(/u],
		["raise SystemExit", /\braise\s+SystemExit\b/u],
		["bare assert in code", /^\s*assert\s+/mu],
	],
	go: [
		["panic(", /\bpanic\s*\(/u],
		["log.Fatal", /\blog\.Fatal[a-z]*\s*\(/u],
		["os.Exit", /\bos\.Exit\s*\(/u],
	],
	java: [
		["System.exit", /\bSystem\.exit\s*\(/u],
		["throw AssertionError", /\bthrow\s+new\s+AssertionError\b/u],
		["Optional.get()", /\bOptional[^;]*\.get\s*\(\s*\)/u],
	],
	kotlin: [
		["!! force non-null", /!!(?:[.\s)]|$)/u],
		["error(", /(?:^|[^.\w])error\s*\(/u],
		["TODO(", /\bTODO\s*\(/u],
	],
	rust: [
		[".unwrap()", /\.unwrap\s*\(\s*\)/u],
		[".expect(", /\.expect\s*\(/u],
		["panic!", /\bpanic!\s*\(/u],
		["unreachable!", /\bunreachable!\s*\(/u],
		["unimplemented!/todo!", /\b(?:unimplemented|todo)!\s*\(/u],
	],
	ruby: [
		["exit!", /\bexit!/u],
		["abort", /\babort\b/u],
	],
	c: [
		["abort(", /\babort\s*\(/u],
		["exit(", /\bexit\s*\(/u],
		["assert(", /\bassert\s*\(/u],
	],
	// Objective-C uses C/Cocoa assertions rather than Swift bang operators.
	"objective-c": [
		["abort(", /\babort\s*\(/u],
		["exit(", /\bexit\s*\(/u],
		["NSAssert", /\bNS(?:C)?Assert\s*\(/u],
	],
	csharp: [
		["Environment.Exit", /\bEnvironment\.Exit\s*\(/u],
		["Environment.FailFast", /\bEnvironment\.FailFast\s*\(/u],
		["Debug.Assert", /\bDebug\.Assert\s*\(/u],
		["null-forgiving !", /[A-Za-z0-9_)\]]!\.(?=[A-Za-z_])/u],
	],
};

const PREVIEW_OPENER = /^\s*(?:#Preview\b|(?:\w+\s+)*struct\s+\w+\s*:\s*PreviewProvider\b)/mu;

interface CrashLine {
	path: string;
	line: number;
	endLine: number;
	language: string;
	operators: string;
	inPreview: boolean;
	testFile: boolean;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const rows: CrashLine[] = [];
		const byKind: Record<string, number> = {};
		for (const file of await work.sources(
			(source) => source.language !== null && LANG_PATTERNS[source.language] !== undefined,
		)) {
			const lang = file.language;
			const patterns = lang === null ? undefined : LANG_PATTERNS[lang];
			if (lang === null || patterns === undefined) {
				continue;
			}
			const preview = lang === "swift" ? file.blocks(PREVIEW_OPENER) : [];
			for (const [kind, test] of patterns) {
				for (const site of file.sites([{ kind, label: kind, test }])) {
					byKind[kind] = (byKind[kind] ?? 0) + 1;
					rows.push({
						path: site.path,
						line: site.line,
						endLine: site.endLine,
						language: lang,
						operators: kind,
						inPreview: preview.some(
							(range) => site.line >= range.start && site.endLine <= range.end,
						),
						testFile: isTestPath(file.path),
					});
				}
			}
		}
		rows.sort((a, b) => Number(a.inPreview || a.testFile) - Number(b.inPreview || b.testFile));
		const inPreview = rows.filter((row) => row.inPreview).length;
		const inTests = rows.filter((row) => row.testFile).length;
		const kinds = Object.entries(byKind).map(([kind, count]) => `${String(count)} ${kind}`);
		const directions =
			rows.length > 0
				? [
						`${rows.length} selected line(s) spell a crash operator: ${listWithin(kinds, DIRECTION_CHARS - 60)}.`,
						`${inPreview} sit inside a preview block and ${inTests} in test files. String-literal text and comments were not scanned.`,
						"A lead matches spelling on one line: it does not know the operand's type, where the value came from, or what bounds it.",
					]
				: [];
		const leads: ReviewLead[] = rows.slice(0, 40).map((row) => ({
			at: {
				file: row.path,
				line: row.line,
				...(row.endLine === row.line ? {} : { endLine: row.endLine }),
			},
			label: LABELS["crash-operator"],
			facts: {
				language: row.language,
				operators: row.operators,
				inPreview: row.inPreview,
				testFile: row.testFile,
			},
		}));
		return ctx.review({
			leads,
			facts: { crashOperatorLines: rows.length, inPreview, inTests },
			directions,
		});
	},
});
