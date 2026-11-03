import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const ALLOWED_SINGLE = new Set(["i", "j", "k", "x", "y", "z", "_"]);
const ACRONYMS = ["API", "URL", "HTTP", "JSON", "XML", "HTML", "CSS", "SQL", "UI", "ID"];
const COMMON_PREFIXES = new Set(
	"get set new old the for all any has can did max min sub pre add top tab nav bar row col end use run log map key tag pop".split(
		" ",
	),
);
const GENERIC =
	/\b(?:let|var)\s+(?<name>data|result|value|item|thing|object|element|stuff)\s*[=:]/u;
const GENERIC_SKIP =
	/URLSession|Codable|JSONDecoder|JSONEncoder|TaskGroup|ForEach|Result<|\.decode|\.encode/u;
const TYPE_SUFFIXES = ["String", "Int", "Array", "Dictionary", "Bool", "Date", "Double", "Float"];

const LABELS = {
	snakeCaseProperty: "A changed property or variable with an underscore in its name.",
	snakeCaseFunction: "A changed function with an underscore in its name.",
	singleLetter: "A changed single-letter variable that is used again within 15 lines.",
	allCapsConstant: "A changed constant written in capitals.",
	acronymCasing: "A changed variable that starts with a capitalized acronym.",
	genericName: "A changed variable with a generic name such as data or result.",
	typeInName: "A changed variable whose name ends with its type.",
	sentinelString: "A changed capitalized string literal used as a value.",
	fileTypeMismatch: "A changed file whose top-level type has another name.",
	sharedPrefix: "Three or more properties in a changed file share a short prefix.",
} as const;
type Cue = keyof typeof LABELS;

function acronymAtStart(name: string): string | null {
	return (
		ACRONYMS.find(
			(acronym) =>
				name.length > acronym.length &&
				name.startsWith(acronym) &&
				/^[A-Z]/u.test(name.slice(acronym.length)),
		) ?? null
	);
}

function topLevelTypes(lines: readonly string[]): { name: string; line: number }[] {
	const types: { name: string; line: number }[] = [];
	let depth = 0;
	for (const [index, line] of lines.entries()) {
		depth += (line.match(/\{/gu) ?? []).length - (line.match(/\}/gu) ?? []).length;
		const name =
			depth <= 1
				? /^\s*(?:(?:public|private|internal|fileprivate|open|final)\s+)*(?:struct|class|enum|protocol|actor)\s+(?<name>\w+)/u.exec(
						line,
					)?.groups?.name
				: undefined;
		if (name !== undefined) {
			types.push({ name, line: index + 1 });
		}
	}
	return types;
}

/** The naming cues of one changed line, with what each names. */
function lineCues(lines: readonly string[], index: number): [Cue, Record<string, string>][] {
	const line = lines[index] ?? "";
	const found: [Cue, Record<string, string>][] = [];
	const comment = line.trimStart().startsWith("//");
	const snake = /\b(?:let|var)\s+(?<name>\w+_\w+)/u.exec(line)?.groups?.name;
	if (!comment && snake !== undefined && !/CodingKey|case\s|@objc|#selector/u.test(line)) {
		found.push(["snakeCaseProperty", { identifier: snake }]);
	}
	const snakeFunction = /func\s+(?<name>\w+_\w+)/u.exec(line)?.groups?.name;
	if (!comment && snakeFunction !== undefined) {
		found.push(["snakeCaseFunction", { identifier: snakeFunction }]);
	}
	const single = /\b(?:let|var)\s+(?<name>[a-zA-Z])\s*[=:]/u.exec(line)?.groups?.name;
	if (
		single !== undefined &&
		!ALLOWED_SINGLE.has(single) &&
		!/ForEach|\.map|\.filter|\.reduce|\.forEach|\.flatMap|\.compactMap/u.test(line) &&
		lines.slice(index + 1, index + 16).some((l) => new RegExp(`\\b${single}\\b`, "u").test(l))
	) {
		found.push(["singleLetter", { identifier: single }]);
	}
	const caps = /\b(?:let|static\s+let|static\s+var)\s+(?<name>[A-Z][A-Z_]{2,})\b/u.exec(line)
		?.groups?.name;
	if (!comment && caps !== undefined) {
		found.push(["allCapsConstant", { identifier: caps }]);
	}
	const variable = /\b(?:let|var)\s+(?<name>\w+)/u.exec(line)?.groups?.name;
	const acronym = variable === undefined ? null : acronymAtStart(variable);
	if (variable !== undefined && acronym !== null) {
		found.push(["acronymCasing", { identifier: variable, acronym }]);
	}
	const sentinel = /"(?<name>[A-Z][A-Z ]{2,})"/u.exec(line)?.groups?.name;
	if (
		!comment &&
		sentinel !== undefined &&
		/[=:(]/u.test(line) &&
		!/\b(?:print|Text|Label|Logger|log\.|description|title|message|NSLocalizedString)\b/u.test(line)
	) {
		found.push(["sentinelString", { value: sentinel }]);
	}
	const generic = GENERIC_SKIP.test(line) ? undefined : GENERIC.exec(line)?.groups?.name;
	if (generic !== undefined) {
		found.push(["genericName", { identifier: generic }]);
	}
	for (const suffix of TYPE_SUFFIXES) {
		const typed = new RegExp(`\\b(?:let|var)\\s+(\\w{3,})${suffix}\\s*[=:]`, "u").exec(line)?.[1];
		if (typed !== undefined && !/^(?:struct|class|enum|typealias|protocol)\s/u.test(line.trim())) {
			found.push(["typeInName", { identifier: `${typed}${suffix}` }]);
		}
	}
	return found;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources(
			(file) => file.language === "swift" && !/Tests?\//u.test(file.path),
		);
		const counts = new Map<Cue, number>();
		const leads: ReviewLead[] = [];
		const cue = (
			key: Cue,
			file: string,
			line: number,
			facts: Record<string, string | number> = {},
		) => {
			counts.set(key, (counts.get(key) ?? 0) + 1);
			if (leads.length < 60) {
				leads.push({ at: { file, line }, label: LABELS[key], facts });
			}
		};
		for (const file of files) {
			if (/import\s+XCTest|import\s+Testing/u.test(file.text)) {
				continue;
			}
			const lines = file.text.split("\n");
			for (const index of lines.keys()) {
				const at = index + 1;
				if (!file.isAdded(at)) {
					continue;
				}
				for (const [key, facts] of lineCues(lines, index)) {
					cue(key, file.path, at, facts);
				}
			}
			const fileName =
				file.path
					.split("/")
					.pop()
					?.replace(/\.swift$/u, "") ?? "";
			const types = topLevelTypes(lines);
			const first = types[0];
			if (
				first !== undefined &&
				types.length < 3 &&
				!types.some((t) => t.name === fileName || t.name === `${fileName}App`)
			) {
				cue("fileTypeMismatch", file.path, first.line, { fileName, typeName: first.name });
			}
			const groups = new Map<string, { names: Set<string>; line: number }>();
			for (const [index, line] of lines.entries()) {
				const name = /\b(?:let|var|@State.*var|@Binding\s+var)\s+(?<name>\w+)/u.exec(line)?.groups
					?.name;
				if (name === undefined || name.length <= 3) {
					continue;
				}
				for (let length = 2; length <= 4 && length < name.length; length += 1) {
					const prefix = name.slice(0, length).toLowerCase();
					if (!COMMON_PREFIXES.has(prefix)) {
						const group = groups.get(prefix) ?? { names: new Set<string>(), line: index + 1 };
						group.names.add(name);
						groups.set(prefix, group);
					}
				}
			}
			for (const [prefix, group] of groups) {
				if (group.names.size >= 3) {
					cue("sharedPrefix", file.path, group.line, { prefix, properties: group.names.size });
				}
			}
		}
		const directions = [
			"Each lead is a changed line, or for a file-level cue a changed file, of non-test Swift. The authored program follows the Swift API Design Guidelines: lowerCamelCase members, UpperCamelCase types, Bool names that read as assertions, and acronyms cased as one word.",
			"A name pattern is not a verdict. Read whether the name is clear at its use site; names fixed by an API, a coding key or a domain term can be right as they are.",
		];
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});
