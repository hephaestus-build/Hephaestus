import { GlassView, isLiquidGlassAvailable } from "expo-glass-effect";
import type { Ref } from "react";
import {
	type LayoutChangeEvent,
	Pressable,
	StyleSheet,
	TextInput,
	useWindowDimensions,
	View,
} from "react-native";

import { Icon } from "@/ui/Icon";
import { useColumnPadding, useStackedText } from "@/ui/layout";
import { space, usePalette } from "@/ui/theme";

export interface ComposerProps {
	/** The draft; kept by the caller so it survives leaving the conversation. */
	text: string;
	onChangeText: (text: string) => void;
	busy: boolean;
	disabled?: boolean;
	/** What the empty field says, including why it is disabled. */
	placeholder: string;
	onSend: (text: string) => void;
	onStop: () => void;
	onLayout?: (event: LayoutChangeEvent) => void;
	ref?: Ref<View>;
	/**
	 * The part of the home indicator or gesture bar the bar itself covers while the keyboard is closed.
	 * It is part of the measured bar, so the transcript scrolls clear of it too.
	 */
	bottomInset?: number;
}

/**
 * Liquid Glass, where the system has it: the composer is a control floating over the conversation, the
 * layer glass is for. The system makes it opaque for Reduce Transparency and bolder for Increase
 * Contrast. Elsewhere, and on Android, it is a plain bar.
 */
const GLASS = isLiquidGlassAvailable();

/** The message field under a conversation: send while idle, stop while Heph is answering. */
export function Composer({
	text,
	onChangeText,
	busy,
	disabled = false,
	placeholder,
	onSend,
	onStop,
	onLayout,
	ref,
	bottomInset = 0,
}: ComposerProps) {
	const palette = usePalette();
	const { fontScale } = useWindowDimensions();
	const largeText = useStackedText();
	// React Native clears a controlled field natively without telling layout the text is gone, so an
	// emptied field kept the height of the message just sent until something else re-measured it. Empty
	// and editable, the field is one line: its own line height at the person's text size, plus its
	// padding. At accessibility sizes the placeholder itself may wrap, so there it keeps its natural size.
	const oneLine =
		text === "" && !disabled && !largeText
			? { maxHeight: Math.max(FIELD_MIN, Math.ceil(LINE * fontScale) + FIELD_PADDING * 2) }
			: undefined;
	// The transcript's column, less the composer's own inset, so the field lines up with the messages.
	const column = useColumnPadding(space.md);
	const canSend = !busy && !disabled && text.trim() !== "";
	const send = () => {
		if (canSend) {
			onSend(text.trim());
		}
	};
	const field = (
		<>
			<TextInput
				testID="heph-input"
				value={text}
				onChangeText={onChangeText}
				placeholder={placeholder}
				placeholderTextColor={palette.tertiaryLabel}
				editable={!disabled}
				multiline
				accessibilityLabel="Message to Heph"
				style={[
					styles.input,
					{ color: palette.label, backgroundColor: GLASS ? "transparent" : palette.fill },
					oneLine,
				]}
			/>
			<Pressable
				testID={busy ? "heph-stop" : "heph-send"}
				onPress={busy ? onStop : send}
				disabled={!busy && !canSend}
				accessibilityRole="button"
				accessibilityLabel={busy ? "Stop the answer" : "Send"}
				accessibilityState={{ disabled: !busy && !canSend }}
				hitSlop={8}
				// The one committing control, in the label colour; a grey disc while there is nothing to send.
				style={[
					styles.button,
					{ backgroundColor: busy || canSend ? palette.primary : palette.fill },
				]}
			>
				<Icon
					name={
						busy
							? { ios: "stop.fill", android: "stop" }
							: { ios: "arrow.up", android: "arrow_upward" }
					}
					size={18}
					weight="bold"
					tintColor={busy || canSend ? palette.onPrimary : palette.tertiaryLabel}
				/>
			</Pressable>
		</>
	);
	if (GLASS) {
		return (
			<View
				ref={ref}
				onLayout={onLayout}
				style={[styles.floating, column, { paddingBottom: space.sm + bottomInset }]}
			>
				<GlassView isInteractive style={styles.capsule}>
					{field}
				</GlassView>
			</View>
		);
	}
	return (
		<View
			ref={ref}
			onLayout={onLayout}
			style={[
				styles.bar,
				column,
				{
					backgroundColor: palette.background,
					borderColor: palette.separator,
					paddingBottom: space.sm + bottomInset,
				},
			]}
		>
			{field}
		</View>
	);
}

/** The field's line height at the default text size, its vertical padding, and its smallest height. */
const LINE = 22;
const FIELD_PADDING = 11;
const FIELD_MIN = 44;

const styles = StyleSheet.create({
	floating: { paddingTop: space.sm },
	// A generous card rather than a thin pill: room for a sentence before it grows.
	capsule: {
		flexDirection: "row",
		alignItems: "flex-end",
		gap: space.sm,
		padding: space.sm,
		borderRadius: 28,
		borderCurve: "continuous",
	},
	bar: {
		flexDirection: "row",
		alignItems: "flex-end",
		gap: space.sm,
		paddingVertical: space.sm,
		borderTopWidth: StyleSheet.hairlineWidth,
	},
	input: {
		flex: 1,
		minHeight: FIELD_MIN,
		maxHeight: 160,
		// Concentric with the glass capsule around it: the capsule's radius less the padding between.
		borderRadius: 20,
		borderCurve: "continuous",
		paddingHorizontal: space.md,
		paddingTop: FIELD_PADDING,
		paddingBottom: FIELD_PADDING,
		fontSize: 17,
		// Explicit, so one line's height is known at any text size; scaled with the font by the system.
		lineHeight: LINE,
	},
	button: {
		width: 36,
		height: 36,
		borderRadius: 18,
		alignItems: "center",
		justifyContent: "center",
		// Centred on a one-line field, and on its last line as it grows.
		marginBottom: 4,
	},
});
