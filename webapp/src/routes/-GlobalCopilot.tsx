import { useRouter } from "@tanstack/react-router";
import { toast } from "sonner";
import { Chat } from "@/components/mentor/Chat";
import { Copilot } from "@/components/mentor/Copilot";
import { defaultPartRenderers } from "@/components/mentor/renderers";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useMentorChat } from "@/hooks/use-mentor-chat";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useAuth } from "@/integrations/auth/AuthContext";
import { useFeatureFlag } from "@/integrations/feature-flags";

export default function GlobalCopilot() {
	// No `onError`: `Chat` renders `status === "error"` inside the transcript, where the reader
	// already is, rather than as a toast away from the conversation that failed.
	const mentorChat = useMentorChat({});

	const router = useRouter();
	const { isAuthenticated, isLoading } = useAuth();
	const { enabled: hasMentorAccess } = useFeatureFlag("MENTOR_ACCESS");
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const { features, isLoading: featuresLoading } = useWorkspaceFeatures(workspaceSlug);

	const handleMessageSubmit = ({ text }: { text: string }) => {
		if (!text.trim()) return;
		mentorChat.sendMessage(text);
	};

	const handleVote = (messageId: string, isUpvote: boolean) => {
		mentorChat.voteMessage(messageId, isUpvote);
	};

	const handleMessageEdit = (messageId: string, content: string) => {
		const messageIndex = mentorChat.messages.findIndex((message) => message.id === messageId);
		if (messageIndex === -1) return;
		mentorChat.setMessages(mentorChat.messages.slice(0, messageIndex));
		mentorChat.sendMessage(content);
	};

	const handleCopy = (content: string) => {
		navigator.clipboard.writeText(content).catch(() => {
			toast.error("Couldn't copy that to the clipboard.");
		});
	};

	if (
		isLoading ||
		featuresLoading ||
		!isAuthenticated ||
		!workspaceSlug ||
		!hasMentorAccess ||
		!features?.mentorEnabled
	) {
		return null;
	}

	return (
		<Copilot
			hasMessages={mentorChat.messages.length > 0}
			onNewChat={() => {
				mentorChat.setMessages([]);
			}}
			onOpenFullChat={() => {
				const threadId = mentorChat.currentThreadId ?? mentorChat.id;
				if (threadId && workspaceSlug) {
					void router.navigate({
						to: "/w/$workspaceSlug/mentor/$threadId",
						params: { threadId, workspaceSlug },
					});
				}
			}}
		>
			<Chat
				messages={mentorChat.messages}
				votes={mentorChat.votes}
				status={mentorChat.status}
				readonly={false}
				attachments={[]}
				onMessageSubmit={handleMessageSubmit}
				onMessageEdit={handleMessageEdit}
				onStop={() => void mentorChat.stop()}
				onFileUpload={() => Promise.resolve([])}
				onAttachmentsChange={() => {}}
				onCopy={handleCopy}
				onVote={handleVote}
				inputPlaceholder="Ask me anything..."
				disableAttachments
				className="h-full max-h-none"
				partRenderers={defaultPartRenderers}
			/>
		</Copilot>
	);
}
