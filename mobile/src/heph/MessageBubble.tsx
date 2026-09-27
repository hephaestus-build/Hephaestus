import { Pressable, StyleSheet, View } from "react-native";

import { AppText } from "@/ui/AppText";
import { HephMark } from "@/ui/HephMark";
import { Markdown } from "@/ui/Markdown";
import { space, usePalette } from "@/ui/theme";

import { Icon } from "@/ui/Icon";
import { type HephMessage, type Interruption, textOf } from "./transcript";

export interface MessageBubbleProps {
	message: HephMessage;
	/** This reply is still arriving. */
	streaming?: boolean;
	/** Reports a finished reply as offensive, harmful or wrong. */
	onReport?: (text: string) => void;
	/**
	 * How an earlier reply ended, when the server recorded it as incomplete. The last reply says so in
	 * the notice under the conversation instead, with its action.
	 */
	incomplete?: Interruption;
}

const INCOMPLETE_NOTE: Record<Interruption, string> = {
	stopped: "This answer stopped before it finished.",
	failed: "This answer could not be finished.",
	"cut-off": "This answer ended early and may be incomplete.",
};

/** What a tool call is doing, in words; its raw arguments and output are never shown. */
interface ToolState {
	key: string;
	label: string;
	failed: boolean;
}

function toolStates(message: HephMessage): ToolState[] {
	return message.parts.flatMap((part, index): ToolState[] => {
		if (!part.type.startsWith("tool-") && part.type !== "dynamic-tool") {
			return [];
		}
		const state = "state" in part && typeof part.state === "string" ? part.state : "";
		if (state === "output-error") {
			return [{ key: `${index}`, label: "A lookup did not finish", failed: true }];
		}
		return [
			{
				key: `${index}`,
				label: state === "output-available" ? "Looked something up" : "Looking something up…",
				failed: false,
			},
		];
	});
}

/** A developer's message in a bubble on the trailing side; Heph's reply as full-width prose, under its name. */
export function MessageBubble({
	message,
	streaming = false,
	onReport,
	incomplete,
}: MessageBubbleProps) {
	const palette = usePalette();
	const text = textOf(message);
	if (message.role === "user") {
		return (
			// A quiet grey bubble: the person's words are context, and the reply below is what they read.
			<View style={styles.userRow}>
				<View
					style={[styles.userBubble, { backgroundColor: palette.fill }]}
					accessibilityLabel={`You said: ${text}`}
				>
					<AppText selectable>{text}</AppText>
				</View>
			</View>
		);
	}
	const tools = toolStates(message);
	// A reply stopped before Heph wrote or looked up anything has nothing to attribute: the notice
	// under the conversation says it stopped, rather than a name above an empty space.
	if (text === "" && !streaming && tools.length === 0 && incomplete === undefined) {
		return null;
	}
	return (
		<View style={styles.reply}>
			{/* Who is speaking, as a thread names its speakers; the face is decoration beside the name. */}
			<View style={styles.speaker}>
				<HephMark size={20} />
				<AppText variant="footnote" weight="semibold" tone="secondaryLabel">
					Heph
				</AppText>
			</View>
			{tools.map((tool) => (
				<View key={tool.key} style={styles.tool} accessible accessibilityLabel={tool.label}>
					<Icon
						name={
							tool.failed
								? { ios: "exclamationmark.circle", android: "error" }
								: { ios: "magnifyingglass", android: "search" }
						}
						size={14}
						tintColor={tool.failed ? palette.warning : palette.secondaryLabel}
					/>
					<AppText variant="footnote" tone="secondaryLabel">
						{tool.label}
					</AppText>
				</View>
			))}
			{text === "" && streaming ? (
				<AppText tone="secondaryLabel" accessibilityLiveRegion="polite">
					Heph is thinking…
				</AppText>
			) : (
				<Markdown markdown={text} streaming={streaming} />
			)}
			{incomplete === undefined ? null : (
				<AppText variant="footnote" tone="secondaryLabel">
					{INCOMPLETE_NOTE[incomplete]}
				</AppText>
			)}
			{streaming || text === "" || onReport === undefined ? null : (
				<Pressable
					testID="heph-report-reply"
					onPress={() => onReport(text)}
					accessibilityRole="button"
					accessibilityLabel="Report this reply"
					accessibilityHint="If it is offensive, harmful or wrong"
					// A footnote-sized control, grown to the 44-point minimum a finger needs.
					hitSlop={{ top: 13, bottom: 13, left: 8, right: 8 }}
					style={styles.report}
				>
					<Icon
						name={{ ios: "flag", android: "flag" }}
						size={13}
						tintColor={palette.secondaryLabel}
					/>
					<AppText variant="footnote" tone="secondaryLabel">
						Report
					</AppText>
				</Pressable>
			)}
		</View>
	);
}

const styles = StyleSheet.create({
	userRow: { alignItems: "flex-end" },
	userBubble: {
		maxWidth: "85%",
		paddingHorizontal: space.md,
		paddingVertical: space.sm,
		borderRadius: 20,
		borderCurve: "continuous",
	},
	reply: { gap: space.sm },
	speaker: { flexDirection: "row", alignItems: "center", gap: space.xs + 2 },
	tool: { flexDirection: "row", alignItems: "center", gap: space.xs },
	report: { flexDirection: "row", alignItems: "center", gap: space.xs, alignSelf: "flex-start" },
});
