// Compiled, never run: the item has its domain type only after a kit validates it.
import { definePracticeScript } from "../lib/core/practice-script.ts";

import { scm } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		// @ts-expect-error The neutral item has no provider fields until a kit validates it.
		void ctx.item.pr_number;
		// @ts-expect-error File reads belong to the SCM kit.
		void ctx.files;
		const work = scm(ctx, "scm.pull_request", "scm.file", "scm.repository");
		if (work.kind === "scm.pull_request") {
			void work.item.pr_number;
		}
		if (work.kind === "scm.file") {
			void work.item.blobId;
		}
		if (work.kind === "scm.repository") {
			void work.item.commitId;
		}
		return ctx.review();
	},
});
