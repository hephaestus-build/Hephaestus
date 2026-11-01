import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	allowMyProductFeedbackPublicMutation,
	editMyProductFeedbackDraftMutation,
	getMyProductFeedbackOptions,
	getMyProductFeedbackQueryKey,
	getProductFeedbackSharingOptions,
	getProductFeedbackSharingQueryKey,
	keepMyProductFeedbackPrivateMutation,
	listMyProductFeedbackQueryKey,
	makeMyProductFeedbackPrivateMutation,
	submitInstanceProductFeedbackMutation,
	submitWorkspaceProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	EditProductFeedbackDraft,
	FeedbackReceipt,
	FeedbackRequest,
	MyProductFeedback,
} from "@/api/types.gen";
import type { ProductFeedbackSharingChoice } from "@/components/product-feedback/ProductFeedbackDialog";
import type { ReceiptReport } from "@/components/product-feedback/ProductFeedbackReceipt";
import { isSettling, type ProductFeedbackAction } from "@/components/product-feedback/sharing-copy";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

const FEEDBACK_SEND = ["product-feedback-send"];

/** How often an open confirmation reads a report that can still change on its own. */
const FOLLOW_INTERVAL_MS = 3000;

const DRAFT_KEPT = "Your draft is still here.";

function submissionError(error: unknown): string {
	const status = problemStatusOf(error);
	if (status === undefined) {
		return `We could not send that. ${DRAFT_KEPT} Check your connection, then try again.`;
	}
	if (status === 429) {
		return `Wait a minute before sending more feedback. ${DRAFT_KEPT}`;
	}
	if (status === 401) {
		return "Your session has expired. Sign in again before sending.";
	}
	if (status === 422) {
		return `Choose whether this can become a public GitHub issue, then send it again. ${DRAFT_KEPT}`;
	}
	return `We could not send that. ${problemDetailOf(error, "The server refused the request.")} ${DRAFT_KEPT}`;
}

export function useSubmitProductFeedback(workspaceSlug: string | undefined) {
	const queryClient = useQueryClient();
	const shared = {
		mutationKey: FEEDBACK_SEND,
		retry: false,
		// A first answer to the sharing question is the person's choice from now on; a 422 means the
		// composer asked too little, so it reads the choice again.
		onSettled: (_data: unknown, error: unknown, variables: { body: FeedbackRequest }) => {
			if (variables.body.share !== undefined || problemStatusOf(error) === 422) {
				void queryClient.invalidateQueries({ queryKey: getProductFeedbackSharingQueryKey() });
			}
		},
	};
	const workspaceMutation = useMutation({ ...submitWorkspaceProductFeedbackMutation(), ...shared });
	const instanceMutation = useMutation({ ...submitInstanceProductFeedbackMutation(), ...shared });
	const mutation = hasText(workspaceSlug) ? workspaceMutation : instanceMutation;
	return {
		isPending: mutation.isPending,
		error: mutation.isError ? submissionError(mutation.error) : undefined,
		/** The answer to the accepted Send, until `reset`. */
		receipt: mutation.data,
		reset: mutation.reset,
		submit: async (body: FeedbackRequest) => {
			if (queryClient.isMutating({ mutationKey: FEEDBACK_SEND }) > 0) {
				return false;
			}
			try {
				await (hasText(workspaceSlug)
					? workspaceMutation.mutateAsync({ path: { workspaceSlug }, body })
					: instanceMutation.mutateAsync({ body }));
				return true;
			} catch {
				return false;
			}
		},
	};
}

/** The composer's question about public issues, read while the composer is open. */
export function useProductFeedbackSharing(enabled: boolean) {
	const query = useQuery({ ...getProductFeedbackSharingOptions(), enabled });
	let sharing: ProductFeedbackSharingChoice;
	if (query.isPending) {
		sharing = enabled ? { status: "loading" } : { status: "off" };
	} else if (query.isError) {
		// An unread choice asks nothing: the server answers 422 when it needs one, and the composer
		// then reads the choice again.
		sharing = { status: "off" };
	} else if (!hasText(query.data.publicRepository)) {
		sharing = query.data.filesToGitHub ? { status: "private" } : { status: "off" };
	} else if (query.data.allowPublic === undefined) {
		sharing = { status: "ask" };
	} else {
		sharing = { status: "remembered", allowPublic: query.data.allowPublic };
	}
	return { sharing, publicRepository: query.data?.publicRepository };
}

/** The receipt's fields read from the answer to Send, before the report itself has been read. */
function fromReceipt(receipt: FeedbackReceipt): ReceiptReport {
	return {
		id: receipt.id,
		state: receipt.state,
		route: receipt.route,
		filedAfter: receipt.filedAfter,
		canKeepPrivate: false,
		canEdit: false,
	};
}

/**
 * Follows an accepted report while its confirmation is open: every few seconds while it can still
 * change on its own, and not at all once it settled or went to the admin email.
 */
export function useFollowedProductFeedback(
	receipt: FeedbackReceipt | undefined,
	open: boolean,
): ReceiptReport | undefined {
	const followable = receipt !== undefined && receipt.state !== "SENT_TO_ADMINS";
	const query = useQuery({
		...getMyProductFeedbackOptions({ path: { feedbackId: receipt?.id ?? "" } }),
		enabled: open && followable,
		refetchInterval: (current) => {
			const state = current.state.data?.state ?? receipt?.state;
			return open && state !== undefined && isSettling(state) ? FOLLOW_INTERVAL_MS : false;
		},
	});
	if (receipt === undefined) {
		return;
	}
	const report = query.data;
	if (report === undefined) {
		return fromReceipt(receipt);
	}
	// The report carries its hold only once held; while it drafts, the answer to Send has it.
	const filedAfter =
		report.filedAfter ?? (report.state === "DRAFTING" ? receipt.filedAfter : undefined);
	return { ...report, filedAfter } satisfies ReceiptReport;
}

/** The person's own changes to a report, shared by the confirmation and Your product feedback. */
export function useMyProductFeedbackActions() {
	const queryClient = useQueryClient();
	const settle = (report: MyProductFeedback) => {
		queryClient.setQueryData(
			getMyProductFeedbackQueryKey({ path: { feedbackId: report.id } }),
			report,
		);
		void queryClient.invalidateQueries({ queryKey: listMyProductFeedbackQueryKey() });
	};
	const refused = (fallback: string) => (error: unknown) => {
		toast.error(fallback, { description: problemDetailOf(error) });
		void queryClient.invalidateQueries({ queryKey: listMyProductFeedbackQueryKey() });
	};
	const keepPrivate = useMutation({
		...keepMyProductFeedbackPrivateMutation(),
		onSuccess: settle,
		onError: refused("We could not keep it private"),
	});
	const edit = useMutation({
		...editMyProductFeedbackDraftMutation(),
		onSuccess: settle,
		onError: refused("We could not save the issue"),
	});
	const makePrivate = useMutation({
		...makeMyProductFeedbackPrivateMutation(),
		onSuccess: (report) => {
			settle(report);
			toast.success("The issue is in the private repository now");
		},
		onError: refused("We could not make the issue private"),
	});
	const allowPublic = useMutation({
		...allowMyProductFeedbackPublicMutation(),
		onSuccess: settle,
		onError: refused("We could not save your choice"),
	});
	const pendingFor = (feedbackId: string): ProductFeedbackAction | undefined => {
		if (keepPrivate.isPending && keepPrivate.variables.path.feedbackId === feedbackId) {
			return "keep-private";
		}
		if (edit.isPending && edit.variables.path.feedbackId === feedbackId) {
			return "edit";
		}
		if (makePrivate.isPending && makePrivate.variables.path.feedbackId === feedbackId) {
			return "make-private";
		}
		if (allowPublic.isPending && allowPublic.variables.path.feedbackId === feedbackId) {
			return "allow-public";
		}
		return undefined;
	};
	return {
		pendingFor,
		keepPrivate: (feedbackId: string) => keepPrivate.mutate({ path: { feedbackId } }),
		edit: async (feedbackId: string, body: EditProductFeedbackDraft) => {
			try {
				await edit.mutateAsync({ path: { feedbackId }, body });
				return true;
			} catch {
				return false;
			}
		},
		makePrivate: (feedbackId: string) => makePrivate.mutate({ path: { feedbackId } }),
		allowPublic: (feedbackId: string, allow: boolean) =>
			allowPublic.mutate({ path: { feedbackId }, body: { allowPublic: allow } }),
	};
}
