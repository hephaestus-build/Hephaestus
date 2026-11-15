import type { ReviewFeedback } from "@/api/types.gen";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { adminWithheldText } from "@/components/practice-vocabulary/feedback-destination-defs";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { hasText } from "@/lib/text";

import { feedbackDisplayMarkdown } from "./feedback-display";

export type FeedbackBodyFeedback = Pick<ReviewFeedback, "destination" | "textHidden"> & {
	body: string | null;
};

export interface FeedbackBodyProps {
	feedback: FeedbackBodyFeedback;
	className?: string;
}

export function FeedbackBody({ feedback, className }: FeedbackBodyProps) {
	const { body } = feedback;
	const withheld = adminWithheldText(feedback.destination);

	// Private-destination text must stay hidden even if the caller supplies it.
	if (withheld) {
		return (
			<Card flush className={className}>
				<CardContent className="space-y-2 py-4">
					<p className="text-muted-foreground">{withheld.detail}</p>
				</CardContent>
			</Card>
		);
	}

	if (feedback.textHidden || !hasText(body)) {
		return (
			<Card flush className={className}>
				<CardContent className="py-4">
					<p className="text-muted-foreground">
						{feedback.textHidden
							? "Feedback text is hidden because access to its sources could not be verified."
							: "No feedback text is recorded here."}
					</p>
				</CardContent>
			</Card>
		);
	}

	return (
		<Card flush className={className}>
			<Tabs defaultValue="rendered" className="gap-0">
				<CardHeader className="flex flex-wrap items-center justify-between gap-2 pt-3 pb-2">
					<TabsList aria-label="How to show the feedback">
						<TabsTrigger value="rendered" className="px-3">
							Rendered
						</TabsTrigger>
						<TabsTrigger value="source" className="px-3">
							Source
						</TabsTrigger>
					</TabsList>
				</CardHeader>
				<CardContent className="pb-4">
					<TabsContent value="rendered" className={UNTRUSTED_MARKDOWN_PROSE}>
						<UntrustedMarkdown>{feedbackDisplayMarkdown(body)}</UntrustedMarkdown>
					</TabsContent>
					<TabsContent value="source">
						<pre className="rounded-md bg-muted p-3 text-xs break-words whitespace-pre-wrap">
							{body}
						</pre>
					</TabsContent>
				</CardContent>
			</Tabs>
		</Card>
	);
}
