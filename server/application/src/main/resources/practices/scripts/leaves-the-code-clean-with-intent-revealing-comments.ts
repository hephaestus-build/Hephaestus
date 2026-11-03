import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm, type Site } from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"debug-output":
		"A selected call that writes debug output, such as print, console.log or println.",
	"todo-fixme-marker": "A selected line with a TODO, FIXME, XXX or HACK marker.",
	"editing-process-note":
		"A selected comment that tells about the editing session, such as FIXED: or CHANGED:.",
	"template-placeholder": "A selected template placeholder, such as <your-name> or REPLACE_ME.",
	"near-empty-new-file": "A new text or Markdown file with one or two lines of content.",
};

const LANG_PATTERNS: Record<string, [string, RegExp][]> = {
	swift: [
		["print(", /(?:^|[^.\w])print\s*\(/u],
		["debugPrint(", /\bdebugPrint\s*\(/u],
		["NSLog(", /\bNSLog\s*\(/u],
		["dump(", /(?:^|[^.\w])dump\s*\(/u],
	],
	typescript: [
		["console.*", /\bconsole\.(?:log|debug|info|warn|error|trace)\s*\(/u],
		["debugger", /\bdebugger\b/u],
	],
	javascript: [
		["console.*", /\bconsole\.(?:log|debug|info|warn|error|trace)\s*\(/u],
		["debugger", /\bdebugger\b/u],
	],
	python: [
		["print(", /(?:^|[^.\w])print\s*\(/u],
		["breakpoint(", /\bbreakpoint\s*\(/u],
		["pprint(", /\bpprint\s*\(/u],
	],
	java: [
		["System.out/err.print", /\bSystem\.(?:out|err)\.print/u],
		["printStackTrace(", /\.printStackTrace\s*\(/u],
	],
	kotlin: [["println(", /\bprintln\s*\(/u]],
	go: [
		["fmt.Print*", /\bfmt\.Print[a-z]*\s*\(/u],
		["println(", /(?:^|[^.\w])println\s*\(/u],
	],
	ruby: [["puts/p/pp", /(?:^|[^.\w])(?:puts|pp?)\s+["'\d:@]/u]],
	rust: [["println!/dbg!/eprintln!", /\b(?:println|eprintln|dbg|print|eprint)\s*!/u]],
};

const TODO_MARKER = /\b(?:TODO|FIXME|XXX|HACK)\b/u;

const PROCESS_NOTE =
	/^\s*(?:\/\/|#|\*)\s*(?:FIX(?:ED)?\s*\d*\s*:|CHANGED?\s*:|selected\s*:|MOVED\s*:|UPDATED?\s*:|REMOVED?\s*:|NEW\s*:)/imu;
const TEMPLATE_PLACEHOLDER = /#\s*TODO:\s*Adjust|<your[- ]|REPLACE[_ ]ME|CHANGE[_ ]ME/iu;

const LISTED = 40;

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const byLang: Record<string, number> = {};
		let debugCandidates = 0;
		let todoCandidates = 0;
		let processNotes = 0;
		let placeholders = 0;

		const sites: Site[] = [];
		for (const file of await work.sources((source) => source.language !== null)) {
			const lang = file.language;
			if (lang === null) {
				continue;
			}
			const debug = file
				.sites(
					(LANG_PATTERNS[lang] ?? []).map(([call, test]) => ({
						kind: call,
						label: LABELS["debug-output"],
						test,
					})),
				)
				.map((site) => ({
					...site,
					lead: { ...site.lead, facts: { ...site.facts, call: site.label } },
				}));
			sites.push(...debug);
			debugCandidates += debug.length;
			byLang[lang] = (byLang[lang] ?? 0) + debug.length;
			for (const [kind, test] of [
				["todo-fixme-marker", TODO_MARKER],
				["editing-process-note", PROCESS_NOTE],
				["template-placeholder", TEMPLATE_PLACEHOLDER],
			] as const) {
				const found = file.sites([{ kind, label: LABELS[kind], test }], { comments: true });
				sites.push(...found);
				if (kind === "todo-fixme-marker") {
					todoCandidates += found.length;
				}
				if (kind === "editing-process-note") {
					processNotes += found.length;
				}
				if (kind === "template-placeholder") {
					placeholders += found.length;
				}
			}
		}
		const leads = sites
			.toSorted((a, b) => (a.path < b.path ? -1 : Number(a.path > b.path)) || a.line - b.line)
			.map((site) => site.lead);
		const directions: string[] = [];
		if (debugCandidates > 0) {
			directions.push(
				`Found ${String(debugCandidates)} debug-output call(s) on selected lines (print/console/log-style) — investigate whether each is leftover debug residue or a deliberate, intent-revealing log.`,
			);
		}
		if (todoCandidates > 0) {
			directions.push(
				`Found ${String(todoCandidates)} TODO/FIXME/XXX/HACK marker(s) selected — investigate whether each is actionable residue or an intentional, tracked note.`,
			);
		}
		if (processNotes + placeholders > 0) {
			directions.push(
				`Found ${String(processNotes)} comment(s) narrating the editing session, ${String(placeholders)} template placeholder(s) — residue of the writing process unless the surrounding text says otherwise.`,
			);
		}
		return ctx.review({
			leads: leads.slice(0, LISTED),
			facts: {
				debugCandidates,
				todoCandidates,
				processNotes,
				placeholders,
				...byLang,
			},
			directions,
		});
	},
});
