import { describe, expect, it } from "vitest";

import { cn, sanitizeText } from "./utils";

describe("cn", () => {
	it("joins conditional and nested inputs while resolving conflicts", () => {
		expect(
			cn("p-2", ["text-sm", [null, undefined, false, "p-4"]], {
				"bg-primary": true,
				"bg-destructive": false,
			}),
		).toBe("text-sm p-4 bg-primary");
		expect(cn()).toBe("");
		expect(cn(null, undefined, false, "", 0)).toBe("");
	});

	it.each([
		["p-2 p-2", "p-2"],
		["p-2 px-4", "p-2 px-4"],
		["px-4 py-2 p-6", "p-6"],
		["size-4 w-6", "size-4 w-6"],
		["w-6 h-8 size-4", "size-4"],
		["bg-background text-foreground text-sm bg-primary", "text-foreground text-sm bg-primary"],
		["w-(--anchor-width) w-72", "w-72"],
		["max-h-(--available-height) max-h-[50vh]", "max-h-[50vh]"],
		["p-2! p-4 p-6!", "p-4 p-6!"],
		["hover:bg-muted bg-background hover:bg-primary", "bg-background hover:bg-primary"],
		[
			"data-open:opacity-0 data-closed:opacity-0 data-open:opacity-100",
			"data-closed:opacity-0 data-open:opacity-100",
		],
		["w-full @md/field-group:w-56 @md/field-group:w-64", "w-full @md/field-group:w-64"],
		["@container/field-group flex @container/card-header", "flex @container/card-header"],
		["[&>svg]:size-4 [&>svg]:size-6", "[&>svg]:size-6"],
		["animate-spin motion-reduce:animate-none", "animate-spin motion-reduce:animate-none"],
		[
			"data-open:animate-in data-open:fade-in-0 data-closed:animate-out",
			"data-open:animate-in data-open:fade-in-0 data-closed:animate-out",
		],
		[
			"font-sans font-medium font-[family-name:var(--font-display)]",
			"font-medium font-[family-name:var(--font-display)]",
		],
		["group/field custom-widget p-2 p-4", "group/field custom-widget p-4"],
	])("merges %s", (input, expected) => {
		expect(cn(input)).toBe(expected);
	});

	it("lets caller classes override variant output", () => {
		expect(cn("inline-flex h-9 px-3 bg-primary", "h-8 px-2", "h-12 bg-destructive")).toBe(
			"inline-flex px-2 h-12 bg-destructive",
		);
	});

	it("reflects mutated conditional inputs after repeated calls", () => {
		const classes = { "bg-primary": true, "bg-destructive": false };
		const spacing = ["p-2"];
		for (let iteration = 0; iteration < 10; iteration++) {
			expect(cn("p-0", classes, spacing)).toBe("bg-primary p-2");
		}
		classes["bg-primary"] = false;
		classes["bg-destructive"] = true;
		spacing[0] = "p-4";
		expect(cn("p-0", classes, spacing)).toBe("bg-destructive p-4");
	});
});

describe("sanitizeText", () => {
	it("removes special function call markers", () => {
		expect(sanitizeText("Hello <has_function_call>world<has_function_call>")).toBe("Hello world");
	});
});
