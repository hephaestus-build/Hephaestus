import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { enclosingType, swiftTypeRanges } from "./swift-views.ts";

const source = `import SwiftUI

struct EventList: View {
    @State private var events: [Event] = []
    var body: some View {
        List(events) { event in
            Text("{ not a brace }") // } neither
        }
        .task { await load() }
    }
    /* a comment with a { brace */
    func load() async {}
}

@Observable
final class EventStore {
    var events: [Event] = []
}

extension EventList {
    static let sample = EventList()
}
`;

void describe("swiftTypeRanges", () => {
	void it("places each type by its braces, ignoring strings and comments", () => {
		const ranges = swiftTypeRanges(source);
		assert.deepEqual(
			ranges.map((r) => [r.kind, r.name, r.isView, r.start, r.end]),
			[
				["struct", "EventList", true, 3, 13],
				["class", "EventStore", false, 16, 18],
				["extension", "EventList", false, 20, 22],
			],
		);
	});

	void it("names the innermost type of a line and none at file scope", () => {
		const ranges = swiftTypeRanges(source);
		assert.equal(enclosingType(ranges, 9)?.name, "EventList");
		assert.equal(enclosingType(ranges, 17)?.name, "EventStore");
		assert.equal(enclosingType(ranges, 1), undefined);
	});

	void it("keeps a nested type inside its parent and the parent open past it", () => {
		const nested = `struct Outer: View {\n    struct Row: View {\n        var body: some View { Text("") }\n    }\n    var body: some View { Row() }\n}\n`;
		const ranges = swiftTypeRanges(nested);
		assert.equal(enclosingType(ranges, 3)?.name, "Row");
		assert.equal(enclosingType(ranges, 5)?.name, "Outer");
	});
});
