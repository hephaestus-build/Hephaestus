import { z } from "zod";

import type { ReviewedWorkRef } from "~/api/types.gen";
import type { WorkspaceChoice } from "~/shared/review-context";

/**
 * The review change the practice review on a provider page can start: asking for a review. It is only
 * ever about the work the worker reads from that tab, and it does not happen there: the page asks the
 * worker to open the extension's own confirmation window (`action.html`), which no provider page can
 * frame or cover. Cancelling a run or retrying its results is a workspace admin's, in the web app.
 */
export const reviewActionSchema = z.strictObject({ kind: z.literal("request-review") });

export type ReviewAction = z.infer<typeof reviewActionSchema>;

/** The opaque handle the confirmation window is opened with; it names one pending action. */
export const actionIntentSchema = z.string().regex(/^[A-Za-z0-9_-]{22,64}$/u);

/** What the confirmation window shows, read afresh from the server when it asks. */
export interface ActionPreview {
	action: ReviewAction;
	instanceHost: string;
	workspace: WorkspaceChoice;
	work: ReviewedWorkRef;
}

/** What became of a confirmed action, in the server's words. */
export interface ActionOutcome {
	kind: "request-review";
	status: "SUBMITTED" | "REFUSED";
	/** The server's own sentence for why nothing started; shown verbatim. */
	reasonDescription?: string;
}
