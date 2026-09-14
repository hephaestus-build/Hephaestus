import { ArrowLeftIcon, MessageSquareOffIcon } from "lucide-react";

import type { PanelState } from "@/components/common/panel-state";
import { Messages } from "@/components/mentor/Messages";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { ChatMessage } from "@/lib/types";

import { UserViewErrorAlert } from "./UserViewErrorAlert";

/** `unreadable`: the thread arrived but its messages are not a transcript this client can render. */
export type UserViewConversationState =
	| PanelState<{ messages: ChatMessage[] }>
	| { status: "unreadable" };

export interface UserViewConversationThreadProps {
	state: UserViewConversationState;
	onBack: () => void;
}

const SKELETON_TURNS = 3;

function TranscriptSkeleton() {
	return (
		<div className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4" aria-hidden>
			{Array.from({ length: SKELETON_TURNS }, (_, index) => (
				<div key={index} className="flex flex-col gap-6">
					<Skeleton className="h-10 w-2/3 self-end rounded-xl" />
					<Skeleton className="h-24 w-5/6 rounded-xl" />
				</div>
			))}
		</div>
	);
}

export function UserViewConversationThread({ state, onBack }: UserViewConversationThreadProps) {
	return (
		<div className="grid gap-4">
			<Button type="button" variant="outline" className="justify-self-start" onClick={onBack}>
				<ArrowLeftIcon aria-hidden />
				Back to conversations
			</Button>
			<Transcript state={state} />
		</div>
	);
}

function Transcript({ state }: Pick<UserViewConversationThreadProps, "state">) {
	switch (state.status) {
		case "loading":
			return <TranscriptSkeleton />;
		case "error":
			return <UserViewErrorAlert error={state.error} onRetry={state.onRetry} />;
		case "unreadable":
			return (
				<Alert variant="warning">
					<MessageSquareOffIcon aria-hidden />
					<AlertTitle>This conversation could not be displayed</AlertTitle>
					<AlertDescription>
						The saved messages are not in a shape this version of Hephaestus can render.
					</AlertDescription>
				</Alert>
			);
		case "ready":
			return (
				<Messages
					messages={state.messages}
					status="ready"
					readonly
					showGreeting={false}
					showThinking={false}
				/>
			);
	}
}
