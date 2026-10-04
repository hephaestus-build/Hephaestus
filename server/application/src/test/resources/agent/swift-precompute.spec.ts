import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { isPracticeModule } from "../../../../../../docker/agents/precompute/lib/practice-contract.ts";
import type { DiffFile } from "../../../../../../docker/agents/precompute/lib/types.ts";

const repositoryRoot = fileURLToPath(new URL("../../../../../../", import.meta.url));

async function stage(slug: string) {
	const root = mkdtempSync(path.join(tmpdir(), "swift-precompute-"));
	mkdirSync(path.join(root, "practices"));
	mkdirSync(path.join(root, "repo/App"), { recursive: true });
	writeFileSync(path.join(root, "package.json"), '{"type":"module"}\n');
	symlinkSync(path.join(repositoryRoot, "docker/agents/precompute/lib"), path.join(root, "lib"));
	const staged = path.join(root, `practices/${slug}.ts`);
	cpSync(
		path.join(
			repositoryRoot,
			`server/application/src/main/resources/practices/precompute/${slug}.ts`,
		),
		staged,
	);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error("script does not export a default function");
	}
	return { root, script: mod.default };
}

const metadata = {
	pr_number: 1,
	pr_url: "https://example.org/team/project/pull/1",
	repository_full_name: "team/project",
	source_branch: "f",
	target_branch: "main",
	commit_sha: "abc123",
};

const view = `import SwiftUI

struct EventList: View {
    @State private var events: [Event] = []
    var body: some View {
        List(events) { Text($0.name) }
            .task {
                let (data, _) = try! await URLSession.shared.data(from: url)
                events = try! JSONDecoder().decode([Event].self, from: data)
            }
    }
}

final class EventStore {
    func load() async {
        let (data, _) = try await URLSession.shared.data(from: url)
    }
}
`;

/** The whole file as added lines, numbered as in the file. */
function whole(file: string, source: string): [string, DiffFile] {
	const lines = source.split("\n");
	return [
		file,
		{
			path: file,
			addedLines: new Map(lines.map((line, i) => [i + 1, line])),
			removedLines: new Map(),
			hunks: [],
		},
	];
}

void test("an I/O call is placed in the type that encloses it: the view's counts, the store's does not", async () => {
	const { root, script } = await stage("keeps-views-free-of-networking-and-persistence");
	try {
		writeFileSync(path.join(root, "repo/App/EventList.swift"), view);
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/EventList.swift", view)]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.line, h.pattern, h.flags.enclosing]),
			[
				[8, "URLSession request", "struct EventList"],
				[9, "JSON coding", "struct EventList"],
			],
		);
		assert.equal(result.metrics.ioCallsInViews, 2);
		assert.equal(result.metrics.filesWithoutCheckout, 0);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("without a checkout the placing is unknown, and the hint is still reported", async () => {
	const { root, script } = await stage("keeps-views-free-of-networking-and-persistence");
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/EventList.swift", view)]),
			metadata,
		);
		assert.equal(result.metrics.filesWithoutCheckout, 1);
		assert.ok(result.hints.every((h) => h.flags.enclosing === "unknown"));
		// The store's call is a candidate too when nothing says which type it lies in.
		assert.equal(result.hints.length, 3);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a new view without a preview is counted per file, a preview elsewhere in the change is a hint", async () => {
	const { root, script } = await stage("ships-a-preview-with-each-new-view");
	try {
		const result = await script(
			path.join(root, "repo"),
			new Map([
				whole("App/EventList.swift", view),
				whole(
					"App/EventRow.swift",
					'struct EventRow: View {\n    var body: some View { Text("") }\n}\n\n#Preview {\n    EventRow()\n}\n',
				),
			]),
			metadata,
		);
		assert.deepEqual(result.metrics, {
			newViews: 2,
			previewsAdded: 1,
			filesWithNewViewAndNoPreview: 1,
		});
		assert.deepEqual(
			result.hints.map((h) => [h.file, h.pattern]),
			[
				["App/EventList.swift", "new view type"],
				["App/EventRow.swift", "new view type"],
				["App/EventRow.swift", "preview"],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("an API use is shown with the usage keys located for it and where, never a verdict on the target", async () => {
	const { root, script } = await stage("declares-permissions-truthfully-at-point-of-use");
	try {
		writeFileSync(
			path.join(root, "repo/project.yml"),
			"targets:\n  App:\n    info:\n      properties:\n        NSCameraUsageDescription: Scans the ticket's QR code\n",
		);
		const source =
			"import CoreLocation\nimport AVFoundation\nlet manager = CLLocationManager()\nmanager.requestWhenInUseAuthorization()\nlet session = AVCaptureSession()\n";
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Scanner.swift", source)]),
			metadata,
		);
		assert.equal(result.metrics.capabilitiesAdded, 2);
		assert.equal(result.metrics.authorizationRequests, 1);
		const said = result.directions.join("\n");
		assert.match(said, /Location — keys located: none/u);
		assert.match(said, /Camera — keys located: NSCameraUsageDescription \(project\.yml\)/u);
		assert.match(said, /does not show the target declares it/u);
		assert.doesNotMatch(said, /No usage key found/u);
		assert.match(said, /A root view can be the feature that needs authorization/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a system picker, an import or an image picker on the library is not permission-bearing access", async () => {
	const { root, script } = await stage("declares-permissions-truthfully-at-point-of-use");
	try {
		const source = [
			"import CoreLocation",
			"import PhotosUI",
			"let picker = PHPickerViewController(configuration: PHPickerConfiguration())",
			"let library = UIImagePickerController()",
			"library.sourceType = .photoLibrary",
			"let place = CLLocationCoordinate2D(latitude: 48.1, longitude: 11.6)",
		].join("\n");
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Picker.swift", source)]),
			metadata,
		);
		assert.equal(result.metrics.capabilitiesAdded, 0);
		assert.deepEqual(result.directions, []);
		// Choosing the camera as the picker's source is the access it is.
		const camera = await script(
			path.join(root, "repo"),
			new Map([whole("App/Picker.swift", `${source}\nlibrary.sourceType = .camera\n`)]),
			metadata,
		);
		assert.equal(camera.metrics.capabilitiesAdded, 1);
		assert.match(camera.directions.join("\n"), /Camera — keys located: none/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a custom font given a size scales, one given a fixed size does not, and a system point size is read", async () => {
	const { root, script } = await stage("makes-ui-accessible-by-default");
	try {
		const source = [
			"struct Course: View {",
			"    var body: some View {",
			'        Text("A").font(.custom("CourseFont", size: 18))',
			'        Text("B").font(.custom("CourseFont", fixedSize: 18))',
			'        Text("C").font(.system(size: 14))',
			"    }",
			"}",
		].join("\n");
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Course.swift", source)]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.line, h.pattern]),
			[
				[3, "scaled custom font"],
				[4, "fixed-size custom font"],
				[5, "system font with a point size"],
			],
		);
		const said = result.directions.join("\n");
		assert.match(said, /^1 custom font\(s\) with fixedSize: added/mu);
		assert.match(said, /^1 system font\(s\) with a point size added/mu);
		assert.doesNotMatch(said, /scaled only with relativeTo/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("rows beyond the cap are counted by kind and said to be unlisted", async () => {
	const { root, script } = await stage("makes-ui-accessible-by-default");
	try {
		const icons = Array.from({ length: 65 }, () => '        Image(systemName: "star")');
		const source = [
			"struct Stars: View {",
			"    var body: some View {",
			...icons,
			"    }",
			"}",
		].join("\n");
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Stars.swift", source)]),
			metadata,
		);
		assert.equal(result.hints.length, 60);
		assert.equal(result.metrics.symbolImages, 65);
		assert.equal(result.metrics.interfaceLinesAdded, 65);
		assert.match(
			result.directions[0] ?? "",
			/^65 matching added line\(s\); the first 60 are listed and 5 are not\./u,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("detached work needs no written reason, and default isolation text is a lead per target, never the project's", async () => {
	const { root, script } = await stage("uses-structured-concurrency-safely");
	try {
		const source = [
			"struct Feed: View {",
			"    var body: some View {",
			'        Button("Load") { Task { await model.load() } }',
			"        Text(model.title).onAppear { Task.detached { await model.prefetch() } }",
			"    }",
			"}",
		].join("\n");
		writeFileSync(path.join(root, "repo/App/Feed.swift"), source);
		const diff = new Map([whole("App/Feed.swift", source)]);
		const plain = await script(path.join(root, "repo"), diff, metadata);
		const said = plain.directions.join("\n");
		assert.match(said, /no written reason is required/u);
		assert.doesNotMatch(said, /stated reason|whether \.task was available/u);
		assert.match(said, /missing @MainActor on an added line does not by itself/u);
		assert.match(said, /leaves the module's default unknown rather than nonisolated/u);
		assert.match(said, /Check the SDK before relying on inherited SwiftUI View isolation/u);
		assert.match(said, /Swallowed failures belong to handles-errors-instead-of-swallowing-them/u);
		// Two targets that disagree, and a SwiftPM setting that is commented out.
		writeFileSync(
			path.join(root, "repo/project.yml"),
			[
				"targets:",
				"  App:",
				"    settings:",
				"      SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor",
				"  Widget:",
				"    settings:",
				"      SWIFT_DEFAULT_ACTOR_ISOLATION: nonisolated",
			].join("\n"),
		);
		writeFileSync(
			path.join(root, "repo/Package.swift"),
			'// .target(name: "Core", swiftSettings: [.defaultIsolation(MainActor.self)])\n',
		);
		const withSettings = await script(path.join(root, "repo"), diff, metadata);
		const leads = withSettings.directions.join("\n");
		assert.match(leads, /project\.yml:4 SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor/u);
		assert.match(leads, /project\.yml:7 SWIFT_DEFAULT_ACTOR_ISOLATION: nonisolated/u);
		assert.match(leads, /Package\.swift:1 \/\/ \.target/u);
		assert.match(
			leads,
			/applies only to its own target or SwiftPM module and build configuration/u,
		);
		assert.doesNotMatch(leads, /sets MainActor as the default|is on the main actor unless/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a change with no reference anywhere says what it scanned and what the inventory holds", async () => {
	const { root, script } = await stage("links-the-change-to-its-issue");
	try {
		mkdirSync(path.join(root, "context"));
		writeFileSync(
			path.join(root, "context/project_inventory.json"),
			JSON.stringify({ issues: [{ number: 4, title: "Add the event map", state: "opened" }] }),
		);
		const result = await script(
			path.join(root, "repo"),
			new Map(),
			{
				...metadata,
				title: "Add the map",
				body: "Shows the events on a map.",
				source_branch: "map",
			},
			path.join(root, "context"),
		);
		assert.equal(result.metrics.referencesFound, 0);
		assert.equal(result.metrics.inventoryOpenIssues, 1);
		assert.equal(result.metrics.linkedWorkItems, -1);
		const referenced = await script(
			path.join(root, "repo"),
			new Map(),
			{
				...metadata,
				title: "Add the map",
				// The template's commented example is not the author's reference.
				body: "<!-- Example: Closes #12 -->\nCloses #4",
				source_branch: "4-add-the-map",
			},
			path.join(root, "context"),
		);
		assert.equal(referenced.metrics.referencesFound, 1);
		assert.equal(referenced.metrics.closingReferences, 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("native platform logging is a logger and standard output a print, a print under a scripts path is marked as tool output", async () => {
	const { root, script } = await stage("logs-through-the-platform-logger");
	try {
		writeFileSync(
			path.join(root, "repo/App/Log.swift"),
			'import OSLog\nlet logger = Logger(subsystem: "app", category: "app")\n',
		);
		const result = await script(
			path.join(root, "repo"),
			new Map([
				whole(
					"App/Store.swift",
					'final class Store {\n    func load() {\n        print("loading")\n        logger.info("loaded")\n        NSLog("loaded %d", count)\n    }\n}\n',
				),
				whole(
					"App/Sync.m",
					'- (void)sync {\n    NSLog(@"synced %@", name);\n    os_log_error(OS_LOG_DEFAULT, "sync failed");\n    printf("synced");\n}\n',
				),
				whole(
					"app/src/main/java/com/example/Feed.kt",
					'class Feed {\n    fun load() {\n        Log.d("Feed", "loaded")\n        println("loaded")\n    }\n}\n',
				),
				whole(
					"app/src/main/java/com/example/Cache.java",
					'class Cache {\n    void evict() {\n        Log.w(TAG, "evicted");\n        System.out.println("evicted");\n    }\n}\n',
				),
				whole("scripts/report.py", 'print("done")\n'),
			]),
			metadata,
		);
		assert.deepEqual(result.metrics, {
			printsAdded: 5,
			loggerCallsAdded: 6,
			printsInToolPaths: 1,
			checkoutHasLogger: 1,
			filesScanned: 5,
			linesAdded: 30,
		});
		assert.deepEqual(
			result.hints.map((h) => [h.pattern, h.flags.kind, h.flags.toolPath]),
			[
				["swift:print(", "print", false],
				["swift:Logger", "logger", false],
				["swift:NSLog", "logger", false],
				["objective-c:NSLog", "logger", false],
				["objective-c:os_log", "logger", false],
				["objective-c:printf(", "print", false],
				["kotlin:android.util.Log", "logger", false],
				["kotlin:println(", "print", false],
				["java:android.util.Log", "logger", false],
				["java:System.out/err", "print", false],
				["python:print(", "print", true],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a print in another language beyond the shared row cap is counted but not reported as a listed row", async () => {
	const { root, script } = await stage("logs-through-the-platform-logger");
	try {
		const prints = Array.from({ length: 40 }, (_, i) => `        print("step ${String(i)}")`);
		const swift = ["final class Store {", "    func load() {", ...prints, "    }", "}"].join("\n");
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Store.swift", swift), whole("scripts/report.py", 'print("done")\n')]),
			metadata,
		);
		assert.equal(result.hints.length, 40);
		assert.ok(result.hints.every((h) => h.pattern === "swift:print("));
		assert.equal(result.metrics.printsAdded, 41);
		// The script's own print is past the 40 rows returned, so no listed row is under a tool path.
		assert.equal(result.metrics.printsInToolPaths, 0);
		assert.match(result.directions[0] ?? "", /^41 diagnostic line\(s\) added; 40 are listed\./u);
		assert.match(result.directions[1] ?? "", /\(0 listed under a tool or script path\)/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a literal and a named asset color are told apart, and the asset's dark appearance is read from the catalog", async () => {
	const { root, script } = await stage("uses-adaptive-colors-for-every-appearance");
	try {
		mkdirSync(path.join(root, "repo/App/Assets.xcassets/Card.colorset"), { recursive: true });
		writeFileSync(
			path.join(root, "repo/App/Assets.xcassets/Card.colorset/Contents.json"),
			'{"colors":[{"idiom":"universal","color":{}},{"appearances":[{"appearance":"luminosity","value":"dark"}],"idiom":"universal","color":{}}]}',
		);
		const source =
			'struct Card: View {\n    var body: some View {\n        Text("x")\n            .foregroundStyle(.white)\n            .background(Color("Card"))\n            .padding()\n            .background(Color(red: 1, green: 1, blue: 1))\n    }\n}\n';
		writeFileSync(path.join(root, "repo/App/Card.swift"), source);
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Card.swift", source)]),
			metadata,
		);
		assert.equal(result.metrics.literalColors, 2);
		assert.match(result.directions.join(" "), /A translucent scrim over an image is content/u);
		assert.equal(result.metrics.systemAdaptiveColors, 0);
		assert.equal(result.metrics.namedAssetColors, 1);
		assert.equal(result.metrics.assetsWithoutDarkAppearance, 0);
		assert.deepEqual(
			result.hints.map((h) => [h.line, h.pattern, h.flags.hasDarkAppearance ?? null]),
			[
				[4, "literal white/black", null],
				[5, "named asset", true],
				[7, "literal RGB", null],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a colorset's appearances are read as JSON whatever the key order, and an unreadable one stays unknown", async () => {
	const { root, script } = await stage("uses-adaptive-colors-for-every-appearance");
	try {
		const colorsets: Record<string, string> = {
			// The dark appearance with its keys in the other order.
			Reordered:
				'{"colors":[{"color":{},"idiom":"universal"},{"color":{},"idiom":"universal","appearances":[{"value":"dark","appearance":"luminosity"}]}]}',
			Single: '{"colors":[{"color":{},"idiom":"universal"}],"info":{"version":1}}',
			Broken: '{"colors":[',
			NoColors: '{"info":{"version":1}}',
			Empty: '{"colors":[]}',
			GamutOnly:
				'{"colors":[{"idiom":"universal","display-gamut":"sRGB","color":{}},{"idiom":"universal","display-gamut":"display-P3","color":{}}]}',
			// Valid JSON whose inspected entries are not the shape a colorset has.
			NullEntry: '{"colors":[{"color":{},"idiom":"universal"},null]}',
			TextAppearances: '{"colors":[{"color":{},"idiom":"universal","appearances":"dark"}]}',
		};
		for (const [name, contents] of Object.entries(colorsets)) {
			mkdirSync(path.join(root, `repo/App/Assets.xcassets/${name}.colorset`), { recursive: true });
			writeFileSync(
				path.join(root, `repo/App/Assets.xcassets/${name}.colorset/Contents.json`),
				contents,
			);
		}
		const source = `struct Card: View {\n    var body: some View {\n${Object.keys(colorsets)
			.map((name) => `        Text("x").foregroundStyle(Color("${name}"))`)
			.join("\n")}\n    }\n}\n`;
		writeFileSync(path.join(root, "repo/App/Card.swift"), source);
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Card.swift", source)]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.flags.asset, h.flags.hasDarkAppearance]),
			[
				["Reordered", true],
				["Single", false],
				["Broken", "unknown"],
				["NoColors", "unknown"],
				["Empty", "unknown"],
				["GamutOnly", false],
				["NullEntry", "unknown"],
				["TextAppearances", "unknown"],
			],
		);
		assert.equal(result.metrics.assetsWithoutDarkAppearance, 2);
		const said = result.directions.join("\n");
		assert.match(said, /no dark luminosity entry/u);
		assert.doesNotMatch(said, /literal in disguise/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a named color resolves only to one colorset, exact name first; two candidates stay unknown", async () => {
	const { root, script } = await stage("uses-adaptive-colors-for-every-appearance");
	try {
		const dark =
			'{"colors":[{"color":{},"idiom":"universal"},{"color":{},"idiom":"universal","appearances":[{"appearance":"luminosity","value":"dark"}]}]}';
		const colorsets: [string, string][] = [
			["App/Assets.xcassets/Brand.colorset", dark],
			["Widget/Assets.xcassets/BRAND.colorset", '{"colors":[{"color":{},"idiom":"universal"}]}'],
			["App/Assets.xcassets/Accent.colorset", dark],
			["Widget/Assets.xcassets/Accent.colorset", dark],
		];
		for (const [folder, contents] of colorsets) {
			mkdirSync(path.join(root, "repo", folder), { recursive: true });
			writeFileSync(path.join(root, "repo", folder, "Contents.json"), contents);
		}
		const source =
			'struct Card: View {\n    var body: some View {\n        Text("x").foregroundStyle(Color("Brand"))\n        Text("y").foregroundStyle(Color("brand"))\n        Text("z").foregroundStyle(Color("Accent"))\n    }\n}\n';
		writeFileSync(path.join(root, "repo/App/Card.swift"), source);
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Card.swift", source)]),
			metadata,
		);
		assert.deepEqual(
			result.hints.map((h) => [h.flags.asset, h.flags.hasDarkAppearance]),
			[
				["Brand", true],
				["brand", "unknown"],
				// The same exact name in two catalogs: which target's the view uses is not here.
				["Accent", "unknown"],
			],
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("system gray colors remain adaptive leads rather than literals", async () => {
	const { root, script } = await stage("uses-adaptive-colors-for-every-appearance");
	try {
		const source =
			'struct Card: View {\n    var body: some View {\n        Text("x").foregroundStyle(.gray)\n        Text("x").background(Color(.systemGray2))\n        Text("x").background(Color(uiColor: .systemGray6))\n    }\n}\n';
		writeFileSync(path.join(root, "repo/App/Card.swift"), source);
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Card.swift", source)]),
			metadata,
		);
		assert.equal(result.metrics.literalColors, 1);
		assert.equal(result.metrics.systemAdaptiveColors, 2);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("framework storage is not direct view I/O", async () => {
	const { root, script } = await stage("keeps-views-free-of-networking-and-persistence");
	try {
		const source = `import SwiftUI
import CoreData
struct Settings: View {
    @AppStorage("compact") private var compact = false
    @SceneStorage("selection") private var selection = ""
    @FetchRequest(sortDescriptors: []) private var items: FetchedResults<Item>
    var body: some View { Text(selection) }
}
`;
		writeFileSync(path.join(root, "repo/App/Settings.swift"), source);
		const result = await script(
			path.join(root, "repo"),
			new Map([whole("App/Settings.swift", source)]),
			metadata,
		);
		assert.equal(result.metrics.ioCallsInViews, 0);
		assert.deepEqual(result.hints, []);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
