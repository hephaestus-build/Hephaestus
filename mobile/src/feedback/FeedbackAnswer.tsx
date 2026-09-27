import { StyleSheet, View } from "react-native";
import type { FeedbackResponse, ReplaceFeedbackResponseData } from "@/api/types.gen";
import { AppText } from "@/ui/AppText";
import { QueryStates, type QueryLike } from "@/ui/QueryStates";
import { space } from "@/ui/theme";
import { FeedbackResponseForm } from "./FeedbackResponse";

export interface FeedbackAnswerProps {
	response: QueryLike<{ saved: FeedbackResponse | undefined }>;
	saveState: "idle" | "saving" | "error";
	onSave: (body: ReplaceFeedbackResponseData["body"]) => void;
	onClear: () => void;
}

/** The developer's answer, wherever the route presents the feedback. */
export function FeedbackAnswer({ response, saveState, onSave, onClear }: FeedbackAnswerProps) {
	return (
		<View style={styles.answer}>
			<View style={styles.header}>
				<AppText variant="headline" accessibilityRole="header">
					Your answer
				</AppText>
				<AppText variant="footnote" tone="secondaryLabel">
					Tell Hephaestus whether this helped and what you did with it. You can change your answer
					or take it back.
				</AppText>
			</View>
			<QueryStates
				query={response}
				loadingLabel="Loading your answer"
				errorTitle="Could not load your answer"
			>
				{({ saved }) => (
					<FeedbackResponseForm
						key={saved?.respondedAt?.toISOString() ?? "none"}
						saved={saved}
						state={saveState}
						onSave={onSave}
						onClear={onClear}
					/>
				)}
			</QueryStates>
		</View>
	);
}

const styles = StyleSheet.create({
	answer: { gap: space.lg },
	header: { gap: space.sm },
});
