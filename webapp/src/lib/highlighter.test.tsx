import { expect, it } from "vite-plus/test";

import { highlightCode, loadHighlighter } from "./highlighter";

it.each(["constructor", "__proto__", "toString", "not-a-language"])(
	"keeps an unlisted language %s as plain text",
	async (language) => {
		await expect(loadHighlighter(language)).resolves.toBeNull();
		await expect(highlightCode("<script>untrusted</script>", language)).resolves.toBeNull();
	},
);
