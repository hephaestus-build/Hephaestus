import { useChat } from "@ai-sdk/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { browser } from "@wxt-dev/browser";
import { useEffect, useRef, useState } from "react";

import { parseThreadMessages } from "@/lib/chat-validation";
import { retryPlan } from "@/lib/mentor-turn";
import type { ChatMessage } from "@/lib/types";
import {
	type MentorConversation,
	MentorPanel,
	type MentorPanelState,
	type MentorPanelProps,
} from "~/components/mentor/MentorPanel";
import { firstMessage, type MentorPanel as MentorPanelAnswer, panelTabOf } from "~/shared/mentor";
import { mentorTransport } from "~/ui/mentor-transport";
import { ask } from "~/ui/rpc-client";
import { useGeneration } from "~/ui/worker-state";

type ReadyPanel = Extract<MentorPanelAnswer, { status: "ready" }>;

const PANEL_KEY = ["mentor-panel"] as const;

function openSettings(): void {
	void browser.runtime.openOptionsPage();
}

/**
 * Asks again whenever the panel's tab navigates or the panel comes back into view, so a conversation
 * never stays open for work the tab has left. The tab id only filters Chrome's events; the worker
 * decides which tab the panel belongs to from what Chrome attests.
 */
function useFollowTab(refresh: () => void): void {
	useEffect(() => {
		const tabId = panelTabOf(location.search);
		const onUpdated = (updated: number, change: { url?: string; status?: string }) => {
			if (updated === tabId && (change.url !== undefined || change.status === "complete")) {
				refresh();
			}
		};
		const onVisible = () => {
			if (document.visibilityState === "visible") {
				refresh();
			}
		};
		browser.tabs.onUpdated.addListener(onUpdated);
		document.addEventListener("visibilitychange", onVisible);
		return () => {
			browser.tabs.onUpdated.removeListener(onUpdated);
			document.removeEventListener("visibilitychange", onVisible);
		};
	}, [refresh]);
}

/** One conversation: the stored one the tab holds, or a new one that starts when the reader sends. */
function useConversation(panel: ReadyPanel | undefined, onTurnSettled: () => void) {
	// Read back only what the tab held when this conversation was opened; a thread this panel starts
	// is already on screen as it streams.
	const [storedId] = useState(panel?.threadId);
	const [threadId] = useState(() => storedId ?? crypto.randomUUID());
	const stored = useQuery({
		queryKey: ["mentor-thread", storedId],
		queryFn: async () => ask({ type: "get-mentor-thread", threadId: storedId ?? "" }),
		enabled: storedId !== undefined,
		staleTime: Number.POSITIVE_INFINITY,
		refetchOnWindowFocus: false,
	});
	const chat = useChat<ChatMessage>({
		id: threadId,
		transport: mentorTransport(threadId),
		generateId: () => crypto.randomUUID(),
		onFinish: onTurnSettled,
		onError: onTurnSettled,
	});
	const { setMessages, status, stop } = chat;
	// A conversation the tab has left, or the reader replaced, stops answering with it.
	useEffect(
		() => () => {
			void stop();
		},
		[stop],
	);
	const restored = useRef(false);
	const parsed = stored.data === undefined ? undefined : parseThreadMessages(stored.data.messages);
	useEffect(() => {
		if (restored.current || parsed === undefined || status !== "ready") {
			return;
		}
		restored.current = true;
		setMessages(parsed);
	}, [parsed, setMessages, status]);
	// The reply the last retry replaced, for a retry of a retry that never started (`retryPlan`).
	const retryTarget = useRef<string | undefined>(undefined);
	let restoreError: string | undefined;
	if (stored.isError) {
		restoreError = stored.error.message;
	} else if (stored.data !== undefined && parsed === undefined) {
		restoreError = "Its earlier messages could not be read.";
	}
	const conversation: MentorConversation = {
		messages: chat.messages,
		status: chat.status,
		error: chat.error?.message,
		restoring:
			storedId !== undefined &&
			restoreError === undefined &&
			(stored.isPending ||
				(parsed !== undefined && parsed.length > 0 && chat.messages.length === 0)),
		restoreError,
	};
	return {
		conversation,
		restore: () => {
			if (storedId !== undefined) {
				void stored.refetch();
			}
		},
		send: (text: string) => {
			if (panel === undefined) {
				return;
			}
			retryTarget.current = undefined;
			// A conversation's first message names the work, visibly, exactly as it is stored.
			const opening = chat.messages.length === 0;
			void chat.sendMessage({ text: opening ? firstMessage(panel.reference, text) : text });
		},
		stop: () => {
			void stop();
		},
		retry: () => {
			const plan = retryPlan(chat.messages, retryTarget.current);
			retryTarget.current = plan.replaces;
			void chat.regenerate(plan.options);
		},
	};
}

function Panel({ state, onReload }: { state: MentorPanelState; onReload: () => void }) {
	const queryClient = useQueryClient();
	const panel = state.status === "ready" ? state : undefined;
	const [fresh, setFresh] = useState(0);
	const refreshPanel = () => {
		void queryClient.invalidateQueries({ queryKey: PANEL_KEY });
	};
	const startNew = useMutation({
		mutationFn: async () => ask({ type: "new-mentor-conversation" }),
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: PANEL_KEY });
			setFresh((count) => count + 1);
		},
	});
	let newConversation: MentorPanelProps["newConversation"];
	if (startNew.isPending) {
		newConversation = { status: "pending" };
	} else if (startNew.isError) {
		newConversation = { status: "error", message: startNew.error.message };
	}
	return (
		<Conversation
			// A conversation lives while the tab shows the same work, until the reader starts a new one.
			key={`${fresh}:${panel?.workspace.slug ?? ""}:${panel?.work.canonicalUrl ?? ""}`}
			state={state}
			panel={panel}
			onReload={onReload}
			onNewConversation={() => startNew.mutate()}
			onTurnSettled={refreshPanel}
			newConversation={newConversation}
		/>
	);
}

function Conversation({
	state,
	panel,
	onReload,
	onNewConversation,
	onTurnSettled,
	newConversation,
}: {
	state: MentorPanelState;
	panel: ReadyPanel | undefined;
	onReload: () => void;
	onNewConversation: () => void;
	onTurnSettled: () => void;
	newConversation: MentorPanelProps["newConversation"];
}) {
	const chat = useConversation(panel, onTurnSettled);
	return (
		<MentorPanel
			state={state}
			conversation={chat.conversation}
			onSend={chat.send}
			onStop={chat.stop}
			onRetry={chat.retry}
			onNewConversation={onNewConversation}
			onReload={() => {
				chat.restore();
				onReload();
			}}
			onOpenSettings={openSettings}
			newConversation={newConversation}
		/>
	);
}

/**
 * The Heph panel's container: what the worker says about the panel's tab, and the conversation held
 * there. A new generation — sign-out, another account or instance, revoked site access — drops all of
 * it in the same tick.
 */
export function MentorView() {
	const generation = useGeneration();
	const queryClient = useQueryClient();
	const panel = useQuery({
		queryKey: PANEL_KEY,
		queryFn: async () => ask({ type: "get-mentor-panel" }),
	});
	const reload = () => {
		void queryClient.invalidateQueries({ queryKey: PANEL_KEY });
	};
	useFollowTab(reload);
	let state: MentorPanelState = { status: "loading" };
	if (panel.data !== undefined) {
		state = panel.data;
	} else if (panel.isError) {
		state = { status: "failed", message: panel.error.message };
	}
	return <Panel key={generation} state={state} onReload={reload} />;
}
