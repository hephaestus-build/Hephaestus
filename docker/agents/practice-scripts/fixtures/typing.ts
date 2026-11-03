import { definePracticeScript } from "@hephaestus/practice-scripts/models";
// Compiled, never run: the slot types follow `meta.models`.
import type { Experimental_DecisionModel, LanguageModel } from "ai";

import { scm } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: { models: { decision: "required", chat: "optional" } },
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
		const decision: Experimental_DecisionModel = ctx.models.decision;
		// @ts-expect-error an optional slot may be undefined
		const chat: LanguageModel = ctx.models.chat;
		// @ts-expect-error an undeclared slot does not exist
		void ctx.models.embedding;
		void decision;
		void chat;
		return ctx.review();
	},
});
