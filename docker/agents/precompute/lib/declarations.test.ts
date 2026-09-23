import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { declarations, enclosingDeclaration } from "./declarations.ts";
import { isTestPath, languageOf } from "./languages.ts";

const swift = `import SwiftUI

struct EventList: View {
    @State private var events: [Event] = []
    var body: some View {
        List(events) { Text("{ not a brace }") } // } neither
            .task { await load() }
    }
    /* a comment { with a brace /* nested */ still a comment */
    func load() async {
        let prompt = """
            Return JSON like {"events": [ { "name": "x" } ]}
            """
        let raw = #"a "quoted" { brace } and \\#(interpolated) text"#
        let nested = "outer \\(events.isEmpty ? "none {" : "some }") done"
    }
}

@Observable
final class EventStore {
    var events: [Event] = []
}

extension Notification.Name {
    static let refreshed = Notification.Name("refreshed")
}
`;

void describe("declarations", () => {
	void it("places Swift types by their braces through strings, interpolation and comments", () => {
		const decls = declarations("swift", swift) ?? [];
		assert.deepEqual(
			decls.map((d) => [d.kind, d.name, d.supertypes, d.start, d.end]),
			[
				["struct", "EventList", "View", 3, 17],
				["class", "EventStore", "", 20, 22],
				["extension", "Notification.Name", "", 24, 26],
			],
		);
		assert.equal(enclosingDeclaration(decls, 12)?.name, "EventList");
		assert.equal(enclosingDeclaration(decls, 1), undefined);
	});

	void it("keeps a nested type inside its parent and the parent open past it", () => {
		const nested = `struct Outer: View {\n    struct Row: View {\n        var body: some View { Text("") }\n    }\n    var body: some View { Row() }\n}\n`;
		const decls = declarations("swift", nested) ?? [];
		assert.equal(enclosingDeclaration(decls, 3)?.name, "Row");
		assert.equal(enclosingDeclaration(decls, 5)?.name, "Outer");
	});

	void it("reads Kotlin supertypes past a constructor and skips template braces", () => {
		const kotlin = `class EventViewModel(private val repo: Repo) : ViewModel(), Refreshable {\n    val label = "count: \${events.map { it.name }.size}"\n    fun refresh() {}\n}\n\ndata class Event(val name: String)\n\nobject Defaults {\n    const val PAGE = 20\n}\n`;
		assert.deepEqual(
			declarations("kotlin", kotlin)?.map((d) => [d.kind, d.name, d.supertypes, d.start, d.end]),
			[
				["class", "EventViewModel", "ViewModel(), Refreshable", 1, 4],
				["object", "Defaults", "", 8, 10],
			],
		);
	});

	void it("reads Java and TypeScript inheritance and text-block braces", () => {
		const java = `public final class EventService implements Service, Closeable {\n    String json = """\n        { "a": 1 }\n        """;\n    record Page(int size) {}\n}\n`;
		assert.deepEqual(
			declarations("java", java)?.map((d) => [d.kind, d.name, d.supertypes, d.start, d.end]),
			[
				["class", "EventService", "Service, Closeable", 1, 6],
				["record", "Page", "", 5, 5],
			],
		);
		const ts = `export class Store extends Base<T> implements Loader {
  label = \`n: \${items.map((i) => { return i; }).length}\`;
}
export interface Loader {
  load(): void;
}
`;
		assert.deepEqual(
			declarations("typescript", ts)?.map((d) => [d.kind, d.name, d.supertypes, d.end]),
			[
				["class", "Store", "Base<T> implements Loader", 3],
				["interface", "Loader", "", 6],
			],
		);
	});

	void it("has no row for an indentation language and says so", () => {
		assert.equal(declarations("python", "class A:\n    pass\n"), null);
	});

	void it("names a language by extension and a test file by convention", () => {
		assert.equal(languageOf("App/EventList.swift"), "swift");
		assert.equal(languageOf("README.md"), null);
		assert.equal(isTestPath("AppTests/EventListTests.swift"), true);
		assert.equal(isTestPath("src/store.test.ts"), true);
		assert.equal(isTestPath("App/Views/EventList.swift"), false);
	});
});
