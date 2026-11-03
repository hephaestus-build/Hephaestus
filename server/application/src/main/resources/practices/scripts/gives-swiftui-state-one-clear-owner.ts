import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { isTestPath, scm } from "@hephaestus/practice-scripts/scm";

const TEST_IMPORT = /import\s+XCTest|import\s+Testing/u;
const VIEW = /:\s*(?:\w+\s*,\s*)*View\s*[,{]/u;
const VALUE_TYPES = new Set(
	"Bool Int Double Float String Date UUID URL CGFloat CGPoint CGSize CGRect Color TimeInterval Data Decimal IndexSet AttributedString".split(
		" ",
	),
);

const LABELS = {
	observableObject: "A changed line that conforms a type to ObservableObject.",
	published: "A changed @Published property.",
	observedObjectCreates: "A changed @ObservedObject property that creates its own instance.",
	observedObject: "A changed @ObservedObject property.",
	stateObject: "A changed @StateObject property.",
	environmentObject: "A changed @EnvironmentObject property.",
	stateObserver: "A changed @State property with didSet or willSet within the next three lines.",
	stateNotPrivate: "A changed @State property that is not declared private.",
	stateWithoutDefault: "A changed non-private @State property with no default value.",
	stateReferenceType: "A changed @State property of a non-value type with no default value.",
	initCreatesModelContainer: "A changed line that creates a ModelContainer inside an init.",
	manyBindings: "A changed view file with five or more @Binding properties.",
} as const;
type Cue = keyof typeof LABELS;

function referenceType(name: string | undefined): boolean {
	return (
		name !== undefined && /^[A-Z]/u.test(name) && !VALUE_TYPES.has(name.replaceAll(/[[\]?]/gu, ""))
	);
}

/** The ownership cues of one changed line of a view file, with what each names. */
function viewLineCues(lines: readonly string[], index: number): [Cue, Record<string, string>][] {
	const line = lines[index] ?? "";
	const found: [Cue, Record<string, string>][] = [];
	const name = /var\s+(?<name>\w+)/u.exec(line)?.groups?.name ?? "unknown";
	if (/@ObservedObject\s/u.test(line)) {
		found.push([/[=]\s*\w+\(/u.test(line) ? "observedObjectCreates" : "observedObject", { name }]);
	}
	if (/@StateObject\s/u.test(line) && !/@Previewable/u.test(line)) {
		found.push(["stateObject", { name }]);
	}
	if (/@EnvironmentObject\s/u.test(line)) {
		found.push(["environmentObject", { name }]);
	}
	if (
		/@State\s/u.test(line) &&
		/\b(?:didSet|willSet)\s*\{/u.test(lines.slice(index, index + 4).join(" "))
	) {
		found.push(["stateObserver", {}]);
	}
	const state =
		/@State\s/u.test(line) && !/@StateObject/u.test(line)
			? /(?<private>private\s+)?var\s+(?<name>\w+)(?:\s*:\s*(?<type>[A-Z]\w+))?/u.exec(line)?.groups
			: undefined;
	if (state !== undefined) {
		const isPrivate = state.private !== undefined;
		const hasDefault = /[=]\s/u.test(line);
		const stateName = state.name ?? "unknown";
		if (!isPrivate && !/#Preview|Previewable/u.test(line)) {
			found.push(["stateNotPrivate", { name: stateName }]);
		}
		if (!isPrivate && !hasDefault) {
			found.push(["stateWithoutDefault", { name: stateName }]);
		}
		if (!hasDefault && referenceType(state.type)) {
			found.push(["stateReferenceType", { name: stateName, type: state.type ?? "" }]);
		}
	}
	return found;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => file.language === "swift" && !isTestPath(file.path));
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
			if (TEST_IMPORT.test(file.text)) {
				continue;
			}
			const lines = file.text.split("\n");
			const { isAdded } = file;
			for (const [index, line] of lines.entries()) {
				if (!isAdded(index + 1)) {
					continue;
				}
				if (/:\s*.*\bObservableObject\b/u.test(line)) {
					cue("observableObject", file.path, index + 1);
				}
				if (/@Published\s/u.test(line)) {
					cue("published", file.path, index + 1);
				}
			}
			if (!VIEW.test(file.text)) {
				continue;
			}
			const inPreview = previewLines(lines);
			let bindings = 0;
			let bindingAdded = false;
			for (const [index, line] of lines.entries()) {
				const at = index + 1;
				if (inPreview.has(at) || line.trimStart().startsWith("//")) {
					continue;
				}
				if (/@Binding\s/u.test(line)) {
					bindings += 1;
					bindingAdded ||= isAdded(at);
				}
				if (!isAdded(at)) {
					continue;
				}
				for (const [key, facts] of viewLineCues(lines, index)) {
					cue(key, file.path, at, facts);
				}
			}
			for (const at of modelContainersInInit(lines)) {
				if (isAdded(at)) {
					cue("initCreatesModelContainer", file.path, at);
				}
			}
			if (bindings >= 5 && bindingAdded) {
				cue("manyBindings", file.path, 1, { bindings });
			}
		}
		const count = (key: Cue) => counts.get(key) ?? 0;
		const legacy =
			count("observableObject") +
			count("published") +
			count("observedObject") +
			count("stateObject") +
			count("environmentObject");
		const directions = [
			"Each lead is a changed line of a changed Swift file; preview blocks and commented lines are skipped. A line pattern cannot see which type owns the state.",
		];
		if (count("observedObjectCreates") > 0) {
			directions.push(
				"An @ObservedObject that creates its own instance is recreated whenever the parent rebuilds the view. Read whether a parent owns and passes this object.",
			);
		}
		if (count("stateObserver") > 0) {
			directions.push(
				"A didSet or willSet on @State does not run for changes made through the view's bindings. Read whether .onChange(of:) is what the code needs.",
			);
		}
		if (count("stateReferenceType") + count("stateWithoutDefault") > 0) {
			directions.push(
				"A @State without a default is set once from the initializer and then keeps its first value. Read whether the parent expects later values to reach the view.",
			);
		}
		if (count("initCreatesModelContainer") > 0) {
			directions.push(
				"A ModelContainer created inside an init is a separate store. Read whether the app already provides one through the environment.",
			);
		}
		if (legacy > 0) {
			directions.push(
				`${String(legacy)} changed line(s) use ObservableObject-era wrappers. The authored practice prefers @Observable with @State, @Bindable and @Environment; read the project's deployment target before asking for a migration.`,
			);
		}
		if (count("manyBindings") > 0) {
			directions.push(
				"A view with five or more @Binding properties can take one @Observable model with @Bindable instead. Read whether the bindings belong to one model.",
			);
		}
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});

function previewLines(lines: readonly string[]): Set<number> {
	const inside = new Set<number>();
	let start = -1;
	let depth = 0;
	for (const [index, line] of lines.entries()) {
		if (/#Preview\b/u.test(line)) {
			start = index;
			depth = 0;
		}
		if (start >= 0) {
			inside.add(index + 1);
			depth += (line.match(/\{/gu) ?? []).length - (line.match(/\}/gu) ?? []).length;
			if (depth <= 0 && index > start) {
				start = -1;
			}
		}
	}
	return inside;
}

function modelContainersInInit(lines: readonly string[]): number[] {
	const found: number[] = [];
	let inInit = false;
	let depth = 0;
	for (const [index, line] of lines.entries()) {
		if (/\binit\s*\(/u.test(line)) {
			inInit = true;
			depth = 0;
		}
		if (inInit) {
			depth += (line.match(/\{/gu) ?? []).length - (line.match(/\}/gu) ?? []).length;
			if (/ModelContainer\s*\(/u.test(line) && !line.trimStart().startsWith("//")) {
				found.push(index + 1);
			}
			if (depth <= 0 && index > 0) {
				inInit = false;
			}
		}
	}
	return found;
}
