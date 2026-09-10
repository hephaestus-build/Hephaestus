import { Link } from "@tanstack/react-router";
import { ShieldCheck } from "lucide-react";
import { useId } from "react";

import { Button } from "@/components/ui/button";

export function WorkspaceMentorPreferenceNotice({
	workspaceSlug,
	reason,
}: {
	workspaceSlug: string;
	reason: "no-ai" | "choice-required" | "unavailable";
}) {
	const titleId = useId();
	return (
		<section
			className="mx-auto flex max-w-xl flex-col items-start gap-4 p-6"
			aria-labelledby={titleId}
		>
			<ShieldCheck className="size-8 text-muted-foreground" aria-hidden />
			<h1 id={titleId} className="text-xl font-semibold">
				{reason === "no-ai"
					? "Heph is off for you in this workspace"
					: reason === "choice-required"
						? "Choose how you want to use AI"
						: "Your chosen AI location is unavailable"}
			</h1>
			<p className="text-sm leading-relaxed text-muted-foreground">
				{reason === "no-ai"
					? "Your No AI preference stops new conversations with Heph and practice reviews about you. Your workspace membership is unchanged."
					: reason === "choice-required"
						? "Choose On-premises, Private cloud, or No AI in Workspace preferences. Nothing is selected for you."
						: "We won’t switch processing locations for you. You can change your preference or ask a workspace owner to check the model assignment."}
			</p>
			<Button
				nativeButton={false}
				render={<Link to="/w/$workspaceSlug/onboarding" params={{ workspaceSlug }} />}
			>
				Workspace preferences
			</Button>
		</section>
	);
}
