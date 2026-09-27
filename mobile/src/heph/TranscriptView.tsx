import { KeyboardAwareLegendList } from "@legendapp/list/keyboard";
import type { LegendListRef } from "@legendapp/list/react-native";
import { type RefObject, useCallback, useEffect, useRef } from "react";
import { StyleSheet, View } from "react-native";
import type { SharedValue } from "react-native-reanimated";

import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { HephMark } from "@/ui/HephMark";
import { useColumnPadding } from "@/ui/layout";
import { StateView } from "@/ui/StateView";
import { space } from "@/ui/theme";

import { endHidden, follows, type ScrollGeometry } from "./conversation-end";
import { MessageBubble } from "./MessageBubble";
import {
	type HephMessage,
	type Interruption,
	recordedInterruption,
	type TranscriptState,
} from "./transcript";

export interface TranscriptViewProps {
	/** Anything but `ready` describes the stored conversation when there is nothing to show yet. */
	state: TranscriptState;
	messages: HephMessage[];
	busy: boolean;
	/** Why the last reply is incomplete, when it is. */
	interruption: Interruption | undefined;
	/** How much of the bottom of the list the composer, and the keyboard under it, cover. */
	covered: number;
	/** Whether the list keeps the end of the conversation in sight as a reply grows. */
	following: boolean;
	/** The reader brought the end into sight, or scrolled it out of sight: `follows` decides. */
	onFollowing: (following: boolean) => void;
	listRef: RefObject<LegendListRef | null>;
	contentInsetEndAdjustment: SharedValue<number>;
	/** The bottom safe area the composer covers while the keyboard is closed. */
	keyboardOffset: number;
	/**
	 * The list's own edges, which it owns rather than leaving to iOS's automatic insets: `top` is the
	 * header drawn over it, `bottom` the safe area the composer's dock pads below the composer. The
	 * libraries measure the end from the insets they report, and those leave out what iOS adds
	 * automatically, so an automatic safe-area inset made the end unreachable by exactly that much.
	 */
	edges: { top: number; bottom: number };
	onReport: (text: string) => void;
	onReload: () => void;
	/** Sends the last question again as a new message; absent when there is no question to send. */
	onRetry: (() => void) | undefined;
}

/** The conversation itself: the stored and streamed messages, and what to do when a reply stopped. */
export function TranscriptView({
	state,
	messages,
	busy,
	interruption,
	covered,
	following,
	onFollowing,
	listRef,
	contentInsetEndAdjustment,
	keyboardOffset,
	edges,
	onReport,
	onReload,
	onRetry,
}: TranscriptViewProps) {
	const column = useColumnPadding();
	const introColumn = useColumnPadding(space.xl);
	// The end is judged against what covers the list, from the scroll view's own reports: its offset as
	// it scrolls, its content as a reply grows, its frame, and whatever the composer and keyboard cover.
	const geometry = useRef<ScrollGeometry>({ offset: 0, viewport: 0, content: 0 });
	// From the reader's touch to the end of their fling. Only these scrolls can stop the list following;
	// its own, and the keyboard's, never report a drag.
	const readerScrolling = useRef(false);
	const judgeEnd = useCallback(() => {
		const { current } = geometry;
		if (current.viewport > 0 && current.content > 0) {
			onFollowing(
				follows({
					following,
					readerScrolling: readerScrolling.current,
					hidden: endHidden(current, covered),
				}),
			);
		}
	}, [covered, following, onFollowing]);
	useEffect(judgeEnd, [judgeEnd]);
	if (state === "loading") {
		return <StateView state="loading" label="Loading the conversation" />;
	}
	if (state === "unreadable") {
		return (
			<StateView
				state="error"
				title="Could not read this conversation"
				message="Hephaestus sent it in a form this version of the app cannot show. Try again, or open it on the web. Until it loads, nothing can be sent here."
				onRetry={onReload}
			/>
		);
	}
	if (state === "failed") {
		return (
			<StateView
				state="error"
				title="Could not load this conversation"
				message="Check your connection and try again."
				onRetry={onReload}
			/>
		);
	}
	return (
		<KeyboardAwareLegendList
			ref={listRef}
			data={messages}
			keyExtractor={(message) => message.id}
			// Each item takes the column's padding itself: the list lays items out absolutely, where padding
			// on its content container would not move them.
			renderItem={({ item, index }) => (
				<View style={column}>
					<MessageBubble
						message={item}
						streaming={busy && index === messages.length - 1 && item.role === "assistant"}
						onReport={onReport}
						// Earlier replies carry their recorded ending; the last one has the notice below.
						incomplete={
							item.role === "assistant" && index < messages.length - 1
								? recordedInterruption(item.metadata)
								: undefined
						}
					/>
				</View>
			)}
			estimatedItemSize={80}
			// Each message holds native text state — selection, a reply still streaming in — that a
			// recycled cell would carry into another message.
			recycleItems={false}
			// Messages sit at the end, above the composer, as a conversation reads. The empty intro does not: with
			// no items the list aligns to the end by growing its content to the scroll view's full height, and
			// iOS then insets that content for the header and the floating composer, which puts the intro below
			// the visible area. Without end alignment it starts under the header, where it can be read.
			alignItemsAtEnd={messages.length > 0}
			initialScrollAtEnd
			// A reply is followed as it grows while its end is in sight; someone reading further up stays
			// where they are. It is also what brings a sent message into view: sending resumes following,
			// and the append is a data change, so no second scroll competes. Whether to follow is decided here, from what the composer and keyboard cover,
			// so the list's own distance-from-end gate, which measures against the whole frame, is opened
			// wide rather than left to veto a reply that grew by more than a few lines at once.
			maintainScrollAtEnd={following}
			maintainScrollAtEndThreshold={Number.POSITIVE_INFINITY}
			// The keyboard lifts the conversation only when its end is on screen; reading earlier messages,
			// it opens over them instead. An empty conversation never lifts: its intro sits at the top, and
			// lifting it would carry it under the header.
			keyboardLiftBehavior={messages.length > 0 ? "whenAtEnd" : "never"}
			onScrollBeginDrag={() => {
				readerScrolling.current = true;
			}}
			onScrollEndDrag={() => {
				readerScrolling.current = false;
			}}
			// A fling keeps moving after the finger lifts, and is still the reader's scroll.
			onMomentumScrollBegin={() => {
				readerScrolling.current = true;
			}}
			onMomentumScrollEnd={() => {
				readerScrolling.current = false;
			}}
			onScroll={({ nativeEvent }) => {
				geometry.current = {
					offset: nativeEvent.contentOffset.y,
					viewport: nativeEvent.layoutMeasurement.height,
					content: nativeEvent.contentSize.height,
				};
				judgeEnd();
			}}
			onContentSizeChange={(_width, height) => {
				geometry.current = { ...geometry.current, content: height };
				judgeEnd();
			}}
			onLayout={({ nativeEvent }) => {
				geometry.current = { ...geometry.current, viewport: nativeEvent.layout.height };
				judgeEnd();
			}}
			contentInsetEndAdjustment={contentInsetEndAdjustment}
			keyboardOffset={keyboardOffset}
			contentInsetAdjustmentBehavior="never"
			// The header's space is content padding, so an empty conversation's intro starts below it at
			// the first offset; the safe area is a static inset keyboard-controller adds its own padding to.
			contentInset={{ top: 0, bottom: edges.bottom }}
			scrollIndicatorInsets={{ top: edges.top, bottom: edges.bottom }}
			contentContainerStyle={[styles.list, { paddingTop: edges.top + space.lg }]}
			keyboardDismissMode="interactive"
			keyboardShouldPersistTaps="handled"
			ListEmptyComponent={
				<View style={[styles.intro, introColumn]}>
					<HephMark size={44} />
					<AppText variant="title" accessibilityRole="header">
						What’s on your mind?
					</AppText>
					<AppText tone="secondaryLabel">
						I’m Heph, an AI mentor. Tell me about your work, a review comment, or a piece of
						feedback. I’ll ask as much as I tell.
					</AppText>
				</View>
			}
			ListFooterComponent={
				interruption === undefined ? null : (
					<View style={[styles.notice, column]} accessibilityLiveRegion="polite">
						<AppText variant="subheadline" tone="secondaryLabel">
							{NOTICE[interruption]}
						</AppText>
						{onRetry === undefined ? null : (
							<Button
								title="Send again"
								variant="secondary"
								testID="heph-retry"
								accessibilityHint="Sends your last question again as a new message"
								onPress={onRetry}
							/>
						)}
					</View>
				)
			}
		/>
	);
}

/**
 * What the notice under an incomplete reply says. It names what happened, never the AI service's own
 * error. Each offers to send the question again when there is one to send.
 */
const NOTICE: Record<Interruption, string> = {
	stopped: "Heph's answer stopped before it finished.",
	failed: "Heph could not finish this answer.",
	"cut-off": "Heph's answer ended early and may be incomplete.",
};

const styles = StyleSheet.create({
	list: { gap: space.lg, paddingVertical: space.lg },
	intro: { gap: space.sm, paddingVertical: space.xl },
	notice: { gap: space.sm },
});
