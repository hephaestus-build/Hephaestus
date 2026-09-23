import { describe, expect, it } from "vitest";

import { vendorMarks } from "./ai-vendor-logos";

describe("vendorMarks", () => {
	it("pairs the platform with the maker, like Azure with OpenAI", () => {
		expect(vendorMarks([{ name: "GPT-5", platform: "AZURE", maker: "OPENAI" }])).toStrictEqual([
			"AZURE",
			"OPENAI",
		]);
	});

	it("names each vendor once and never more than two", () => {
		expect(
			vendorMarks([
				{ name: "GPT-5", platform: "OPENAI", maker: "OPENAI" },
				{ name: "Claude", platform: "ANTHROPIC", maker: "ANTHROPIC" },
				{ name: "Llama", platform: "OLLAMA", maker: "META" },
			]),
		).toStrictEqual(["OPENAI", "ANTHROPIC"]);
	});

	it("shows nothing for a model it cannot recognise", () => {
		expect(vendorMarks([{ name: "Team model" }])).toStrictEqual([]);
	});
});
