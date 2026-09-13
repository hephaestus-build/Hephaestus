import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { Chat } from "@/components/mentor/Chat";
import { defaultPartRenderers } from "@/components/mentor/renderers";
import { Skeleton } from "@/components/ui/skeleton";
import { useMentorChat } from "@/hooks/use-mentor-chat";
import { mentorPreferenceReason } from "@/lib/mentor-preference";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor/$threadId")({
	remountDeps: ({ params }) => params,
	component: ThreadContainer,
});

function ThreadContainer() {
	const { threadId, workspaceSlug } = Route.useParams();
	const preference = useQuery(getMemberOnboardingOptions({ path: { workspaceSlug } }));
	const readonly = !preference.data || Boolean(mentorPreferenceReason(preference.data));

	// No `onError`: `Chat` renders `status === "error"` inside the transcript, where the reader
	// already is, rather than as a toast away from the conversation that failed.
	const mentorChat = useMentorChat({ threadId });

	const handleMessageSubmit = ({ text }: { text: string }) => {
		if (!text.trim()) return;
		mentorChat.sendMessage(text);
	};

	const handleVote = (messageId: string, isUpvote: boolean) => {
		mentorChat.voteMessage(messageId, isUpvote);
	};

	const handleCopy = (content: string) => {
		navigator.clipboard.writeText(content).catch(() => {
			toast.error("Couldn't copy that to the clipboard.");
		});
	};

	const handleMessageEdit = (messageId: string, content: string) => {
		const idx = mentorChat.messages.findIndex((m) => m.id === messageId);
		if (idx === -1) return;
		mentorChat.setMessages(mentorChat.messages.slice(0, idx));
		mentorChat.sendMessage(content);
	};

	if (mentorChat.isThreadLoading) {
		return (
			<div className="flex flex-col flex-1 min-h-0">
				<div className="relative flex min-h-0 flex-1 flex-col">
					<div className="flex-1 overflow-y-auto p-4 sm:p-6">
						<div className="flex flex-col w-full pb-16 min-w-0 gap-8 flex-1 pt-4 relative mx-auto md:max-w-3xl">
							<div className="flex items-start gap-3 justify-end">
								<div className="space-y-2 max-w-[75%] text-right">
									<Skeleton className="h-4 w-56 ml-auto" />
									<Skeleton className="h-4 w-28 ml-auto" />
								</div>
							</div>

							<div className="flex items-start gap-3">
								<Skeleton className="h-8 w-8 rounded-full" />
								<div className="space-y-2 max-w-[75%]">
									<Skeleton className="h-4 w-40" />
									<Skeleton className="h-4 w-64" />
									<Skeleton className="h-4 w-32" />
								</div>
							</div>

							<div className="flex items-start gap-3 justify-end">
								<div className="space-y-2 max-w-[75%] text-right">
									<Skeleton className="h-4 w-75 ml-auto" />
									<Skeleton className="h-4 w-34 ml-auto" />
									<Skeleton className="h-4 w-53 ml-auto" />
								</div>
							</div>

							<div className="flex items-start gap-3">
								<Skeleton className="h-8 w-8 rounded-full" />
								<div className="space-y-2 max-w-[75%]">
									<Skeleton className="h-4 w-72" />
									<Skeleton className="h-4 w-52" />
									<Skeleton className="h-4 w-24" />
								</div>
							</div>
						</div>
					</div>

					<div className="flex flex-col gap-2 items-center w-full px-4 pb-2 -mt-20 relative z-10 bg-gradient-to-t from-muted dark:from-background/30 from-60% to-transparent pt-8">
						<div className="w-full max-w-3xl space-y-2">
							<Skeleton className="h-20 flex-1" />
						</div>
						<Skeleton className="h-3 w-64" />
					</div>
				</div>
			</div>
		);
	}

	if (mentorChat.threadError) {
		return (
			<div className="h-full flex items-center justify-center p-6">
				<div className="text-center">
					<p className="text-destructive mb-4">
						Failed to load conversation. Thread may not exist or you don't have access to it.
					</p>
					<p className="text-sm text-muted-foreground">
						Try refreshing the page or go back to the main chat.
					</p>
				</div>
			</div>
		);
	}

	if (!mentorChat.threadDetail) {
		return (
			<div className="h-full flex items-center justify-center p-6">
				<div className="text-center">
					<p className="text-muted-foreground">Conversation not found.</p>
				</div>
			</div>
		);
	}

	return (
		<div className="flex flex-col flex-1 min-h-0">
			<Chat
				messages={mentorChat.messages}
				votes={mentorChat.votes}
				status={mentorChat.status}
				readonly={readonly}
				attachments={[]}
				onMessageSubmit={handleMessageSubmit}
				onMessageEdit={readonly ? undefined : handleMessageEdit}
				onStop={() => void mentorChat.stop()}
				onReload={
					readonly
						? undefined
						: () => {
								mentorChat.clearError();
								void mentorChat.regenerate();
							}
				}
				onFileUpload={() => Promise.resolve([])}
				onAttachmentsChange={() => {}}
				onCopy={handleCopy}
				onVote={handleVote}
				inputPlaceholder="Continue the conversation..."
				disableAttachments
				className="h-full"
				partRenderers={defaultPartRenderers}
			/>
		</div>
	);
}
