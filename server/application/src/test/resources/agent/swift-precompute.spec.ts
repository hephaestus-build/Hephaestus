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

void test("a capability is paired with its usage key from the checkout's project.yml", async () => {
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
		assert.equal(result.metrics.capabilitiesWithoutUsageKey, 1);
		assert.equal(result.metrics.authorizationRequests, 1);
		assert.ok(result.directions.some((d) => d.includes("No usage key found for CoreLocation")));
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

void test("a print beside an existing logger is a lead, a print under a scripts path is marked as tool output", async () => {
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
					'final class Store {\n    func load() {\n        print("loading")\n        logger.info("loaded")\n    }\n}\n',
				),
				whole("scripts/report.py", 'print("done")\n'),
			]),
			metadata,
		);
		assert.deepEqual(result.metrics, {
			printsAdded: 2,
			loggerCallsAdded: 1,
			printsInToolPaths: 1,
			checkoutHasLogger: 1,
			filesScanned: 2,
			linesAdded: 9,
		});
		assert.deepEqual(
			result.hints.map((h) => [h.pattern, h.flags.kind, h.flags.toolPath]),
			[
				["swift:print(", "print", false],
				["swift:Logger", "logger", false],
				["python:print(", "print", true],
			],
		);
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
		assert.equal(result.metrics.adaptiveColors, 1);
		assert.equal(result.metrics.singleAppearanceAssets, 0);
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
