import { useChat } from "@ai-sdk/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as Crypto from "expo-crypto";
import * as WebBrowser from "expo-web-browser";
import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, AppState } from "react-native";
import {
	deleteThreadMutation,
	getInAppFeedbackOptions,
	getThreadOptions,
	getThreadQueryKey,
	listThreadsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { webUrl } from "@/instance/instance";
import { currentSessionKey, useSessionKey } from "@/session/authority";
import { useSession } from "@/session/session-store";
import { useWorkspace } from "@/workspace/workspace-context";
import { openingDraft, readDraft, writeDraft } from "./drafts";
import { createHydration, storedToApply } from "./hydration";
import { grantSharing, sharingGranted } from "./sharing";
import {
	composerState,
	type HephMessage,
	interruptionOf,
	questionToResend,
	type ReplyEnding,
	parseTranscript,
	transcriptState,
} from "./transcript";
import { hephTransport } from "./transport";
import { useHephAccess } from "./use-heph-access";

/** Session-scoped conversation state. The screen owns navigation, keyboard geometry and scrolling. */
export function useConversation(
	params: { threadId: string; about?: string; starter?: string },
	{
		onCreated,
		onDeleted,
		onTurnStarted,
	}: { onCreated: (threadId: string) => void; onDeleted: () => void; onTurnStarted: () => void },
) {
	const isNew = params.threadId === "new";
	const [threadId] = useState(() => (isNew ? Crypto.randomUUID() : params.threadId));
	const session = useSession();
	const workspace = useWorkspace();
	const queryClient = useQueryClient();
	const { access } = useHephAccess();
	const path = { workspaceSlug: workspace.workspaceSlug, threadId };
	const thread = useQuery({
		...getThreadOptions({ path }),
		enabled: !isNew && access === "available",
	});
	const signedIn = session.status === "signedIn" ? session : undefined;
	const epoch = signedIn?.epoch ?? 0;
	const apiBaseUrl = signedIn?.instance.apiBaseUrl ?? "";
	const scope = { epoch, workspaceSlug: workspace.workspaceSlug };
	const [transport] = useState(() => hephTransport(apiBaseUrl, workspace.workspaceSlug, threadId));

	// A conversation opened from a piece of feedback or a starter question begins with a draft naming
	// it. The feedback comes from what its list already read; nothing is fetched or sent on its behalf.
	const known = useQuery({
		...getInAppFeedbackOptions({ path: { workspaceSlug: workspace.workspaceSlug } }),
		enabled: false,
	});
	const [draft, setDraft] = useState(() =>
		openingDraft(
			readDraft(scope, threadId),
			known.data?.find((item) => item.id === params.about),
			params.starter,
		),
	);
	const changeDraft = (text: string) => {
		setDraft(text);
		writeDraft(scope, threadId, text);
	};

	// How the last reply ended is known here at once — stopped by the person, or how the model's turn
	// finished — before the server's record can be read back. Sending or retrying starts a new turn.
	const [ended, setEnded] = useState<ReplyEnding | undefined>();
	const { messages, setMessages, status, stop, sendMessage } = useChat<HephMessage>({
		id: threadId,
		transport,
		generateId: () => Crypto.randomUUID(),
		onFinish: ({ isAbort, finishReason }) => {
			setEnded({ aborted: isAbort, ...(finishReason === undefined ? {} : { finishReason }) });
			void queryClient.invalidateQueries({
				queryKey: listThreadsQueryKey({ path: { workspaceSlug: workspace.workspaceSlug } }),
			});
			void queryClient.invalidateQueries({ queryKey: getThreadQueryKey({ path }) });
			// The conversation exists now: it is read back and refreshed like any stored one, under the
			// same id it was started with.
			if (params.threadId === "new") {
				onCreated(threadId);
			}
		},
	});
	const hydration = useRef(createHydration());
	const busy = status === "submitted" || status === "streaming";

	// The stored transcript replaces the local one when it is newer than what was sent here and still
	// holds it: after opening, after a finished reply is read back, and after the server recorded a turn
	// the app did not see finish.
	const threadData = thread.data;
	const fetchedAt = thread.dataUpdatedAt;
	const stored = useMemo(() => parseTranscript(threadData?.messages), [threadData]);
	useEffect(() => {
		const next = storedToApply(hydration.current, {
			busy,
			fetchedAt,
			stored,
			local: messages,
		});
		if (next !== undefined) {
			setMessages(next);
		}
	}, [stored, fetchedAt, busy, messages, setMessages]);

	// Leaving the conversation — another thread, another workspace, signing out — ends the turn here.
	useEffect(
		() => () => {
			void stop();
		},
		[stop],
	);

	// iOS suspends the stream in the background and the server records the turn as interrupted; coming
	// back, the app reads what was kept rather than guessing.
	const { refetch } = thread;
	useEffect(() => {
		const subscription = AppState.addEventListener("change", (state) => {
			if (state === "active" && !isNew) {
				void refetch();
			}
		});
		return () => {
			subscription.remove();
		};
	}, [isNew, refetch]);

	// Nothing reaches the AI service behind Heph until the person agrees, for this conversation; saying
	// no, or opening the privacy notice, leaves the draft as it was and sends nothing.
	const sessionKey = useSessionKey();
	const mounted = useRef(false);
	useEffect(() => {
		mounted.current = true;
		return () => {
			mounted.current = false;
		};
	}, []);
	const sharing = { sessionKey, workspaceSlug: workspace.workspaceSlug, threadId };
	const withSharing = (send: () => void) => {
		if (sharingGranted(sharing)) {
			send();
			return;
		}
		Alert.alert(
			"Share this with Heph's AI service?",
			`Heph answers with an AI service chosen by the people who run ${signedIn?.instance.label ?? "this Hephaestus"}. To answer, it receives your message, this conversation so far and work from this workspace that Hephaestus has connected. Their privacy notice says which service that is and what it keeps.`,
			[
				{ text: "Cancel", style: "cancel" },
				{
					text: "Privacy notice",
					onPress: () => {
						if (signedIn !== undefined) {
							void WebBrowser.openBrowserAsync(`${webUrl(signedIn.instance)}/privacy`);
						}
					},
				},
				{
					text: "Share and send",
					onPress: () => {
						// The alert outlives the screen that raised it: a confirmation from a session that has
						// since ended or changed sends nothing.
						if (currentSessionKey() !== sharing.sessionKey || !mounted.current) {
							return;
						}
						grantSharing(sharing);
						send();
					},
				},
			],
		);
	};

	const deleteThread = useMutation({
		...deleteThreadMutation(),
		onError: () => {
			Alert.alert(
				"Could not delete this conversation",
				"It is still there. Try again in a moment.",
			);
		},
		onSuccess: () => {
			void queryClient.invalidateQueries({
				queryKey: listThreadsQueryKey({ path: { workspaceSlug: workspace.workspaceSlug } }),
			});
			onDeleted();
		},
	});

	const confirmDelete = () =>
		Alert.alert(
			"Delete this conversation?",
			"This conversation will be deleted and cannot be restored.",
			[
				{ text: "Cancel", style: "cancel" },
				{ text: "Delete", style: "destructive", onPress: () => deleteThread.mutate({ path }) },
			],
		);

	// A new turn clears the last ending; stored history applies only once it contains this message.
	const startTurn = (text: string) => {
		onTurnStarted();
		setEnded(undefined);
		hydration.current.sentAt = Date.now();
		void sendMessage({ text });
	};
	// Sending the last question again is a new message with its own id; resending the old one is refused.
	const resend = questionToResend(messages);
	const interruption = interruptionOf({
		busy,
		failed: status === "error",
		ended,
		messages,
	});
	let read: "pending" | "error" | "success" = "success";
	if (thread.isPending) {
		read = "pending";
	} else if (thread.isError) {
		read = "error";
	}
	const view = transcriptState({
		isNew,
		shown: messages.length,
		fetch: read,
		readable: stored !== undefined,
	});
	const composer = composerState(access, view);

	return {
		isNew,
		title: thread.data?.title,
		access,
		messages,
		busy,
		view,
		composer,
		draft,
		changeDraft,
		interruption,
		confirmDelete,
		refetch: thread.refetch,
		send: (text: string) =>
			withSharing(() => {
				changeDraft("");
				startTurn(text);
			}),
		resend: resend === undefined ? undefined : () => withSharing(() => startTurn(resend)),
		stop: () => {
			void stop();
		},
	};
}
