import { toast } from "sonner";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import { deferred } from "@/test/async";

import { copyRichText } from "./clipboard";

/** Holds the formats it is given, as the browser's `ClipboardItem` does. */
class ClipboardItemStub {
	readonly items: Record<string, Promise<Blob>>;

	constructor(items: Record<string, Promise<Blob>>) {
		this.items = items;
	}
}

/** Each written item's formats, read as text the way a paste reads them. */
const pasted: Record<string, string>[] = [];

const content = {
	text: "## Activity\n\n- [Title](https://example.com)",
	html: "<h2>Activity</h2>",
};

describe("copyRichText", () => {
	const write = vi.fn<(items: ClipboardItemStub[]) => Promise<void>>();
	const writeText = vi.fn<(text: string) => Promise<void>>();

	beforeEach(() => {
		// The browser reads every format it was handed, and fails the write if one fails.
		write.mockImplementation(async (items) => {
			for (const item of items) {
				const formats: Record<string, string> = {};
				for (const [type, blob] of Object.entries(item.items)) {
					const resolved = await blob;
					formats[type] = await resolved.text();
				}
				pasted.push(formats);
			}
		});
		writeText.mockResolvedValue();
		Object.defineProperty(navigator, "clipboard", {
			value: { write, writeText },
			configurable: true,
		});
	});

	afterEach(() => {
		vi.unstubAllGlobals();
		vi.restoreAllMocks();
		write.mockReset();
		writeText.mockReset();
		pasted.length = 0;
	});

	it("writes Markdown and HTML as one item, starting before the content has arrived", async () => {
		vi.stubGlobal("ClipboardItem", ClipboardItemStub);
		const success = vi.spyOn(toast, "success");
		const gathered = deferred<typeof content>();

		const copied = copyRichText(gathered.promise, () => "Copied 1 item as Markdown");
		// The write is already under way in the press's own task, as Safari requires.
		expect(write).toHaveBeenCalledOnce();
		gathered.resolve(content);
		await copied;

		const [formats] = pasted;
		assert(formats);
		expect(formats).toStrictEqual({ "text/plain": content.text, "text/html": content.html });
		expect(writeText).not.toHaveBeenCalled();
		expect(success).toHaveBeenCalledWith("Copied 1 item as Markdown");
	});

	it("falls back to the Markdown as plain text where the rich write is refused", async () => {
		vi.stubGlobal("ClipboardItem", ClipboardItemStub);
		write.mockRejectedValue(new Error("NotAllowedError"));

		await copyRichText(Promise.resolve(content), () => "Copied");

		expect(writeText).toHaveBeenCalledWith(content.text);
	});

	it("tells the reader when the content could not be gathered", async () => {
		vi.stubGlobal("ClipboardItem", ClipboardItemStub);
		const failure = vi.spyOn(toast, "error");

		await copyRichText(Promise.reject(new Error("offline")), () => "Copied");

		expect(failure).toHaveBeenCalledWith("Couldn't copy that to the clipboard.");
	});
});
