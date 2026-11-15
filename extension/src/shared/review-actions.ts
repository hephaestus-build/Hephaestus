import { z } from "zod";

import type { ItemRef } from "~/api/types.gen";
import type { WorkspaceChoice } from "~/shared/review-context";

/** Review requests require the extension's own confirmation window, bound to the worker's current tab. */
export const reviewActionSchema = z.strictObject({ kind: z.literal("request-review") });

export type ReviewAction = z.infer<typeof reviewActionSchema>;

/** The opaque handle the confirmation window is opened with; it names one pending action. */
export const actionIntentSchema = z.string().regex(/^[A-Za-z0-9_-]{22,64}$/u);

/** What the confirmation window shows, read afresh from the server when it asks. */
export interface ActionPreview {
	action: ReviewAction;
	instanceHost: string;
	workspace: WorkspaceChoice;
	item: ItemRef;
}

/**
 * What became of a confirmed action, based on the server's answer. A newer server can answer with a
 * status that this version does not know: `UNRECOGNIZED` claims neither a started nor a refused review.
 */
export interface ActionOutcome {
	kind: "request-review";
	status: "SUBMITTED" | "REFUSED" | "UNRECOGNIZED";
	/** The shared explanation for the server's refusal code. */
	reasonDescription?: string;
}
