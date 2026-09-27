import { useState } from "react";
import { Pressable, StyleSheet, TextInput, View } from "react-native";

import type { FeedbackResponse as Response, FeedbackResponseRequest } from "@/api/types.gen";
import { FEEDBACK_RESOLUTION, FEEDBACK_USEFULNESS } from "@/practice/vocabulary";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { radius, space, usePalette } from "@/ui/theme";

type Usefulness = NonNullable<FeedbackResponseRequest["usefulness"]>;
type Resolution = NonNullable<FeedbackResponseRequest["resolution"]>;

const USEFULNESS: { value: Usefulness; label: string }[] = (["HELPFUL", "UNHELPFUL"] as const).map(
	(value) => ({
		value,
		label: FEEDBACK_USEFULNESS[value].label,
	}),
);

const RESOLUTIONS: { value: Resolution; label: string }[] = (
	["ADDRESSED", "NOT_APPLICABLE", "DISPUTED"] as const
).map((value) => ({ value, label: FEEDBACK_RESOLUTION[value].label }));

export interface FeedbackResponseProps {
	/** The saved answer; undefined when there is none yet. */
	saved: Response | undefined;
	state: "idle" | "saving" | "error";
	onSave: (response: FeedbackResponseRequest) => void;
	/** Takes the answer back entirely. */
	onClear: () => void;
}

/**
 * The developer's answer to one piece of feedback: whether it helped, and what they did with it.
 * Disagreeing asks for a reason, so the answer says what was wrong rather than only that something was.
 */
export function FeedbackResponseForm({ saved, state, onSave, onClear }: FeedbackResponseProps) {
	const palette = usePalette();
	const [usefulness, setUsefulness] = useState(saved?.usefulness);
	const [resolution, setResolution] = useState(saved?.resolution);
	const [comment, setComment] = useState(saved?.comment ?? "");
	const needsComment = resolution === "DISPUTED" && comment.trim() === "";
	const changed =
		usefulness !== saved?.usefulness ||
		resolution !== saved?.resolution ||
		comment.trim() !== (saved?.comment ?? "");
	const hasAnswer = usefulness !== undefined || resolution !== undefined;

	return (
		<View style={styles.form}>
			<Choice
				label="Was this useful?"
				disabled={state === "saving"}
				options={USEFULNESS}
				selected={usefulness}
				onSelect={(value) => setUsefulness(value === usefulness ? undefined : value)}
			/>
			<Choice
				label="What did you do with it?"
				disabled={state === "saving"}
				options={RESOLUTIONS}
				selected={resolution}
				onSelect={(value) => setResolution(value === resolution ? undefined : value)}
			/>
			<TextInput
				testID="response-comment"
				editable={state !== "saving"}
				value={comment}
				onChangeText={setComment}
				multiline
				placeholder={
					resolution === "DISPUTED"
						? "Say why you disagree (needed)"
						: "Anything to add? (optional)"
				}
				placeholderTextColor={palette.tertiaryLabel}
				accessibilityLabel={resolution === "DISPUTED" ? "Why you disagree" : "Comment"}
				style={[styles.comment, { color: palette.label, backgroundColor: palette.fill }]}
			/>
			{state === "error" ? (
				<AppText tone="danger" accessibilityLiveRegion="assertive">
					Your answer was not saved. Try again.
				</AppText>
			) : null}
			<Button
				testID="response-save"
				title={saved === undefined ? "Send answer" : "Update answer"}
				pending={state === "saving"}
				disabled={!hasAnswer || needsComment || !changed}
				onPress={() =>
					onSave({
						...(usefulness === undefined ? {} : { usefulness }),
						...(resolution === undefined ? {} : { resolution }),
						...(comment.trim() === "" ? {} : { comment: comment.trim() }),
					})
				}
			/>
			{saved === undefined ? null : (
				<Button
					title="Take my answer back"
					variant="plain"
					onPress={onClear}
					disabled={state === "saving"}
				/>
			)}
		</View>
	);
}

function Choice<T extends string>({
	label,
	options,
	selected,
	onSelect,
	disabled,
}: {
	label: string;
	disabled: boolean;
	options: { value: T; label: string }[];
	selected: T | undefined;
	onSelect: (value: T) => void;
}) {
	const palette = usePalette();
	return (
		<View style={styles.choice} accessibilityRole="radiogroup" accessibilityLabel={label}>
			<AppText variant="subheadline" weight="semibold">
				{label}
			</AppText>
			<View style={styles.options}>
				{options.map((option) => {
					const active = option.value === selected;
					return (
						<Pressable
							key={option.value}
							testID={`response-${option.value}`}
							onPress={() => onSelect(option.value)}
							accessibilityRole="radio"
							disabled={disabled}
							accessibilityState={{ checked: active, disabled }}
							style={[
								styles.option,
								{
									backgroundColor: active ? palette.primary : palette.fill,
								},
							]}
						>
							<AppText
								variant="subheadline"
								weight="semibold"
								tone={active ? "onPrimary" : "label"}
							>
								{option.label}
							</AppText>
						</Pressable>
					);
				})}
			</View>
		</View>
	);
}

const styles = StyleSheet.create({
	form: { gap: space.lg },
	choice: { gap: space.sm },
	options: { flexDirection: "row", flexWrap: "wrap", gap: space.sm },
	option: {
		// The smallest target a finger reliably hits.
		minHeight: 44,
		justifyContent: "center",
		paddingHorizontal: space.md,
		borderRadius: radius.pill,
	},
	comment: {
		minHeight: 88,
		borderRadius: radius.control,
		borderCurve: "continuous",
		padding: space.md,
		fontSize: 16,
		textAlignVertical: "top",
	},
});
