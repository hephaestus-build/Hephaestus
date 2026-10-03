import { definePlugin } from "@oxlint/plugins";

import { noManualQueryKey } from "./rules/no-manual-query-key.ts";
import { noNonAsciiFilename } from "./rules/no-non-ascii-filename.ts";
import { noNondeterministicRender } from "./rules/no-nondeterministic-render.ts";
import { noRedundantInTheDocument } from "./rules/no-redundant-in-the-document.ts";
import { noStoryA11yOverride } from "./rules/no-story-a11y-override.ts";
import { noWithinCanvasElement } from "./rules/no-within-canvas-element.ts";
import { playMustAssert } from "./rules/play-must-assert.ts";
import { preferAutoStoryTitle } from "./rules/prefer-auto-story-title.ts";
import { svgNeedsAccessibleName } from "./rules/svg-needs-accessible-name.ts";
import { typedStoryMeta } from "./rules/typed-story-meta.ts";

import { steUiText } from "./rules/ste-ui-text.ts";

export default definePlugin({
	meta: { name: "hephaestus" },
	rules: {
		"ste-ui-text": steUiText,
		"no-manual-query-key": noManualQueryKey,
		"no-non-ascii-filename": noNonAsciiFilename,
		"no-nondeterministic-render": noNondeterministicRender,
		"no-redundant-in-the-document": noRedundantInTheDocument,
		"no-story-a11y-override": noStoryA11yOverride,
		"no-within-canvas-element": noWithinCanvasElement,
		"play-must-assert": playMustAssert,
		"prefer-auto-story-title": preferAutoStoryTitle,
		"svg-needs-accessible-name": svgNeedsAccessibleName,
		"typed-story-meta": typedStoryMeta,
	},
});
