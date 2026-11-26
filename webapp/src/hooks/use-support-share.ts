import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
	createSupportShareMutation,
	listOwnSupportSharesQueryKey,
	previewSupportShareMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	SupportShareDialogProps,
	SupportSharePreviewState,
} from "@/components/support-share/SupportShareDialog";
import { problemStatusOf } from "@/lib/problem-detail";

export interface SupportShareFlow {
	/** Opens the dialog for one question, with only its diagnostics until the person adds the messages. */
	open: (turnId: string) => void;
	dialog: SupportShareDialogProps;
}

/**
 * The person's act that lets one instance admin read one of their questions (ADR 0164). The preview
 * is asked again whenever its choice changes, so what the person confirms is what the server would
 * show; a share whose messages changed since is refused, and the preview is read again.
 */
export function useSupportShare(workspaceSlug: string): SupportShareFlow {
	const queryClient = useQueryClient();
	const [turnId, setTurnId] = useState<string | undefined>();
	const [includeText, setIncludeText] = useState(false);
	const [recipientId, setRecipientId] = useState<string | undefined>();
	const [changed, setChanged] = useState(false);
	const preview = useMutation(previewSupportShareMutation());
	const create = useMutation({
		...createSupportShareMutation(),
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: listOwnSupportSharesQueryKey() });
		},
	});

	const askPreview = (turn: string, withText: boolean) => {
		preview.mutate({ path: { workspaceSlug, turnId: turn }, body: { includeText: withText } });
	};

	let previewState: SupportSharePreviewState = { status: "loading" };
	if (preview.isError) {
		previewState = {
			status: "error",
			error: preview.error,
			onRetry: () => {
				if (turnId !== undefined) {
					askPreview(turnId, includeText);
				}
			},
		};
	} else if (preview.isSuccess) {
		previewState = { status: "ready", preview: preview.data };
	}

	return {
		open: (turn) => {
			setTurnId(turn);
			setIncludeText(false);
			setRecipientId(undefined);
			setChanged(false);
			create.reset();
			askPreview(turn, false);
		},
		dialog: {
			open: turnId !== undefined,
			onOpenChange: (open) => {
				if (!open) {
					setTurnId(undefined);
					preview.reset();
					create.reset();
				}
			},
			preview: previewState,
			includeText,
			onIncludeTextChange: (next) => {
				setIncludeText(next);
				setChanged(false);
				if (turnId !== undefined) {
					askPreview(turnId, next);
				}
			},
			recipientId,
			onRecipientChange: setRecipientId,
			changed,
			// A refusal because the messages changed shows as its own notice, with the preview read again.
			shareError:
				create.isError && problemStatusOf(create.error) !== 409 ? create.error : undefined,
			sharing: create.isPending,
			onShare: () => {
				if (turnId === undefined || recipientId === undefined || !preview.isSuccess) {
					return;
				}
				const { textDigest } = preview.data;
				create.mutate(
					{
						path: { workspaceSlug, turnId },
						body: {
							recipientAccountId: recipientId,
							includeText,
							...(textDigest === undefined ? {} : { textDigest }),
						},
					},
					{
						onError: (error) => {
							if (problemStatusOf(error) === 409) {
								setChanged(true);
								askPreview(turnId, includeText);
							}
						},
					},
				);
			},
			shared: create.data,
		},
	};
}
