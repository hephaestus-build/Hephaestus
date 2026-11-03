import { definePracticeScript, type ReviewLead } from "@hephaestus/practice-scripts";
import { languageOf, scm, type FileTree } from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"public-declaration":
		"A declaration that the change adds and that the rules of its language export.",
	"internal-declaration":
		"A declaration that the change adds and that the rules of its language keep internal or private.",
};

const PRODUCT_MANIFESTS: [RegExp, (text: string) => boolean][] = [
	[/(?:^|\/)Package\.swift$/u, (t) => /\.library\s*\(/u.test(t)],
	[
		/(?:^|\/)package\.json$/u,
		(t) =>
			!/"private"\s*:\s*true/u.test(t) && /"(?:main|module|exports|types|typings)"\s*:/u.test(t),
	],
	[/(?:^|\/)pom\.xml$/u, (t) => !/<packaging>\s*pom\s*<\/packaging>/u.test(t)],
	[
		/(?:^|\/)build\.gradle(?:\.kts)?$/u,
		(t) =>
			/(?:java-library|maven-publish|`maven-publish`|com\.vanniktech\.maven\.publish)/u.test(t),
	],
	[/(?:^|\/)setup\.py$/u, (t) => /(?:packages\s*=|find_packages\s*\()/u.test(t)],
	[/(?:^|\/)setup\.cfg$/u, (t) => /\[options\][^[]*packages\s*=/u.test(t)],
	[/(?:^|\/)pyproject\.toml$/u, (t) => /(?:\[project\]|\[tool\.poetry\]|packages\s*=)/u.test(t)],
	[/(?:^|\/)go\.mod$/u, (t) => /^module\s+\S+/mu.test(t)],
];

const MANIFEST_NAMES = ["swift", "json", "xml", "gradle", "kts", "py", "cfg", "toml", "mod"];

interface ExportRule {
	decl: RegExp;
	isPublic: (line: string) => boolean;
}

const EXPORT_RULES: Record<string, ExportRule> = {
	swift: {
		decl: /\b(?:func|class|struct|enum|protocol|extension|var|let|typealias|actor|init)\b/u,
		isPublic: (l) => /\b(?:public|open)\b/u.test(l),
	},
	typescript: {
		decl: /\b(?:function|class|interface|type|enum|const|let|var|namespace)\b/u,
		isPublic: (l) => /\bexport\b/u.test(l),
	},
	javascript: {
		decl: /\b(?:function|class|const|let|var)\b/u,
		isPublic: (l) => /\bexport\b/u.test(l),
	},
	java: {
		decl: /\b(?:class|interface|enum|record|void|[A-Z][A-Za-z0-9_<>[\]]*)\s+[A-Za-z_]\w*\s*[({]/u,
		isPublic: (l) => /\bpublic\b/u.test(l),
	},
	kotlin: {
		decl: /\b(?:fun|class|interface|object|val|var)\b/u,
		isPublic: (l) => !/\b(?:private|internal|protected)\b/u.test(l),
	},
	python: {
		decl: /^\s*(?:def|class)\s+[A-Za-z_]/u,
		isPublic: (l) => !/^\s*(?:def|class)\s+_/u.test(l),
	},
	go: {
		decl: /^\s*(?:func|type|var|const)\s+[A-Za-z_]/u,
		isPublic: (l) => /^\s*(?:func|type|var|const)\s+(?:\([^)]*\)\s*)?[A-Z]/u.test(l),
	},
};

async function declaresPublicProduct(tree: FileTree): Promise<boolean> {
	for (const ext of MANIFEST_NAMES) {
		const { paths } = await tree.list(`**/*.${ext}`);
		for (const manifestPath of paths) {
			const matcher = PRODUCT_MANIFESTS.find(([re]) => re.test(manifestPath));
			if (!matcher) {
				continue;
			}
			const file = await tree.read(manifestPath);
			const text = file.status === "FOUND" ? file.text : "";
			if (matcher[1](text)) {
				return true;
			}
		}
	}
	return false;
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const change = work.changedFiles();
		const { head } = work;
		const hasPublicProduct = head !== null && (await declaresPublicProduct(head));

		const leads: ReviewLead[] = [];
		let changedPublicSymbols = 0;
		let changedInternalSymbols = 0;
		let filesScanned = 0;
		let linesAdded = 0;
		for (const [path, df] of change) {
			const lang = languageOf(path);
			if (lang === null) {
				continue;
			}
			const rule = EXPORT_RULES[lang];
			if (!rule) {
				continue;
			}
			filesScanned += 1;
			for (const [line, content] of df.addedLines) {
				if (isCommentLine(content, lang)) {
					continue;
				}
				linesAdded += 1;
				if (!rule.decl.test(content)) {
					continue;
				}
				const isPublic = rule.isPublic(content);
				if (isPublic) {
					changedPublicSymbols += 1;
				} else {
					changedInternalSymbols += 1;
				}
				if (leads.length < 40) {
					leads.push({
						at: { change: path, line },
						label: LABELS[isPublic ? "public-declaration" : "internal-declaration"],
						facts: { language: lang, exported: isPublic },
					});
				}
			}
		}

		const directions: string[] = [];
		if (!hasPublicProduct) {
			directions.push(
				"No public library/framework product was detected in the repo manifests (no SwiftPM .library, non-private package.json entry, publishable Maven/Gradle package, distributable Python package, or Go module).",
				"Investigate whether this repo exposes any public API surface a consumer depends on before deciding documentation was owed.",
			);
		}
		if (changedPublicSymbols > 0) {
			directions.push(
				`The diff adds ${changedPublicSymbols} exported/public declaration(s)${hasPublicProduct ? " in a repo that ships a public product" : ""} — investigate whether their documented behaviour or signature changed and whether that change is reflected in docs/comments.`,
			);
		}
		if (changedInternalSymbols > 0 && changedPublicSymbols === 0) {
			directions.push(
				`The diff adds ${changedInternalSymbols} declaration(s) but none are exported/public (e.g. modifier-less Swift is internal, non-exported TS, package-private Java, leading-underscore Python).`,
				"Investigate whether any public-facing behaviour actually changed before expecting public-API documentation.",
			);
		}
		if (leads.length === 0) {
			directions.push(...noMatchDirections(linesAdded, filesScanned));
		}

		return ctx.review({
			leads,
			facts: {
				hasPublicProduct: hasPublicProduct ? 1 : 0,
				changedPublicSymbols,
				changedInternalSymbols,
				filesScanned,
				linesAdded,
			},
			directions,
		});
	},
});

function isCommentLine(content: string, language: string): boolean {
	const trimmed = content.trimStart();
	if (language === "python" || language === "ruby") {
		return trimmed.startsWith("#");
	}
	return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}

function noMatchDirections(linesAdded: number, filesScanned: number): string[] {
	return [
		`Scanned ${String(linesAdded)} added lines in ${String(filesScanned)} files for this practice's line patterns; none matched.`,
		"A pattern sees one line: what spans lines or has no keyword, such as an early return, a discarded result or a missing else, is yours to enumerate from the diff.",
	];
}
