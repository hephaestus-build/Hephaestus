import { definePlugin } from "@oxlint/plugins";

import { focusOutlineInForcedColors } from "./rules/focus-outline-in-forced-colors.ts";
import { noManualQueryKey } from "./rules/no-manual-query-key.ts";
import { noNonAsciiFilename } from "./rules/no-non-ascii-filename.ts";
import { noNondeterministicRender } from "./rules/no-nondeterministic-render.ts";
import { noRawDocsUrl } from "./rules/no-raw-docs-url.ts";
import { noRedundantInTheDocument } from "./rules/no-redundant-in-the-document.ts";
import { noStoryA11yOverride } from "./rules/no-story-a11y-override.ts";
import { noUnsafeDefinitionLookup } from "./rules/no-unsafe-definition-lookup.ts";
import { noWithinCanvasElement } from "./rules/no-within-canvas-element.ts";
import { playMustAssert } from "./rules/play-must-assert.ts";
import { preferAutoStoryTitle } from "./rules/prefer-auto-story-title.ts";
import { svgNeedsAccessibleName } from "./rules/svg-needs-accessible-name.ts";
import { typedStoryMeta } from "./rules/typed-story-meta.ts";

import { uiTextVoice } from "./rules/ui-text-voice.ts";

export default definePlugin({
	meta: { name: "hephaestus" },
	rules: {
		"ui-text-voice": uiTextVoice,
		"no-manual-query-key": noManualQueryKey,
		"no-unsafe-definition-lookup": noUnsafeDefinitionLookup,
		"no-non-ascii-filename": noNonAsciiFilename,
		"no-nondeterministic-render": noNondeterministicRender,
		"no-raw-docs-url": noRawDocsUrl,
		"focus-outline-in-forced-colors": focusOutlineInForcedColors,
		"no-redundant-in-the-document": noRedundantInTheDocument,
		"no-story-a11y-override": noStoryA11yOverride,
		"no-within-canvas-element": noWithinCanvasElement,
		"play-must-assert": playMustAssert,
		"prefer-auto-story-title": preferAutoStoryTitle,
		"svg-needs-accessible-name": svgNeedsAccessibleName,
		"typed-story-meta": typedStoryMeta,
	},
});
