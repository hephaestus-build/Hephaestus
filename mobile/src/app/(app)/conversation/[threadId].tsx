import { useKeyboardChatComposerInset } from "@legendapp/list/keyboard";
import type { LegendListRef } from "@legendapp/list/react-native";
import { GlassView, isLiquidGlassAvailable } from "expo-glass-effect";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useHeaderHeight } from "expo-router/react-navigation";
import { useRef, useState } from "react";
import { Platform, Pressable, StyleSheet, View } from "react-native";
import { KeyboardStickyView, useKeyboardState } from "react-native-keyboard-controller";
import { useReducedMotion } from "react-native-reanimated";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Composer } from "@/heph/Composer";
import { coveredBottom } from "@/heph/conversation-end";
import { TranscriptView } from "@/heph/TranscriptView";
import { useConversation } from "@/heph/use-conversation";
import { ACCESS_EXPLANATION } from "@/heph/use-heph-access";
import { setPendingReport } from "@/report/report";
import { AppText } from "@/ui/AppText";
import { HeaderAction } from "@/ui/HeaderAction";
import { StateView } from "@/ui/StateView";
import { radius, space, usePalette } from "@/ui/theme";

export default function HephConversation() {
	const params = useLocalSearchParams<{ threadId: string; about?: string; starter?: string }>();
	const router = useRouter();
	const palette = usePalette();
	const [following, setFollowing] = useState(true);
	const conversation = useConversation(params, {
		onCreated: (threadId) => router.setParams({ threadId }),
		onDeleted: () => router.back(),
		onTurnStarted: () => setFollowing(true),
	});
	const {
		isNew,
		title,
		access,
		messages,
		busy,
		view,
		composer,
		draft,
		changeDraft,
		interruption,
		confirmDelete,
	} = conversation;

	// Above the tabs, nothing else keeps the composer off the home indicator or gesture bar. The composer
	// floats over the end of the transcript, which is inset by the composer's measured height; opened,
	// the keyboard covers the bottom inset, so the sticky view and the list rise by the keyboard less the
	// inset, as keyboard-controller's chat guide describes. On iOS the home indicator is kept out of the
	// measured composer: the dock pads it, and the list takes it as its own static bottom inset, because
	// the libraries measure the end from the insets they report and never see iOS's automatic ones.
	// Android's composer pads the gesture bar itself.
	const { bottom: bottomInset } = useSafeAreaInsets();
	const measuredInset = Platform.OS === "ios" ? 0 : bottomInset;
	const headerHeight = useHeaderHeight();
	// The list's own edges: the header over it on iOS, and the safe area the dock pads under the composer.
	const edges = {
		top: Platform.OS === "ios" ? headerHeight : 0,
		bottom: bottomInset - measuredInset,
	};
	const listRef = useRef<LegendListRef>(null);
	const composerRef = useRef<View>(null);
	const { contentInsetEndAdjustment, onComposerLayout } = useKeyboardChatComposerInset(
		listRef,
		composerRef,
	);
	// Whether the list keeps the end in sight as a reply grows. The reader scrolling away stops it, and
	// "Jump to latest" offers the way back; sending, or scrolling back to the end, resumes it.
	const [composerHeight, setComposerHeight] = useState(0);
	const keyboardHeight = useKeyboardState((keyboard) => keyboard.height);
	const covered = coveredBottom({
		composer: composerHeight,
		dockPadding: edges.bottom,
		keyboard: keyboardHeight,
		safeBottom: bottomInset,
	});
	const reduceMotion = useReducedMotion();
	// Jump to latest is the one scroll the app asks for itself; following keeps the end in sight from
	// then on. keyboard-controller is never frozen: a freeze drops keyboard events rather than deferring
	// them, which leaves the list's inset without the keyboard until it next moves.
	const jumpToLatest = () => {
		setFollowing(true);
		void listRef.current?.scrollToEnd({ animated: !reduceMotion });
	};

	if (access === "workspace-off" || access === "no-access") {
		return (
			<>
				<Stack.Screen options={{ title: "Heph" }} />
				<StateView
					state="empty"
					icon={{ ios: "lock", android: "lock" }}
					{...ACCESS_EXPLANATION[access]}
				/>
			</>
		);
	}

	return (
		<View
			style={[styles.screen, { backgroundColor: palette.background }]}
			testID="heph-conversation"
		>
			<Stack.Screen
				options={{
					title: isNew ? "New conversation" : (title ?? "Conversation"),
					headerLargeTitleEnabled: false,
				}}
			/>
			{isNew ? null : (
				<ConversationActions
					onNew={() =>
						router.replace({ pathname: "/conversation/[threadId]", params: { threadId: "new" } })
					}
					onDelete={confirmDelete}
				/>
			)}
			<TranscriptView
				state={view}
				messages={messages}
				busy={busy}
				interruption={interruption}
				covered={covered}
				following={following}
				onFollowing={setFollowing}
				listRef={listRef}
				contentInsetEndAdjustment={contentInsetEndAdjustment}
				keyboardOffset={bottomInset}
				edges={edges}
				onReport={(text) => {
					setPendingReport({ subject: "heph-reply", text });
					router.push("/report");
				}}
				onReload={() => {
					void conversation.refetch();
				}}
				onRetry={conversation.resend}
			/>
			<KeyboardStickyView
				pointerEvents="box-none"
				offset={{ closed: 0, opened: bottomInset }}
				style={[styles.dock, { paddingBottom: edges.bottom }]}
			>
				{/* Rides on top of the composer, wherever the keyboard puts it. */}
				{following || messages.length === 0 ? null : (
					<Pressable
						testID="heph-jump-to-latest"
						accessibilityRole="button"
						hitSlop={6}
						accessibilityLabel="Jump to the latest message"
						onPress={jumpToLatest}
						style={styles.jump}
					>
						{GLASS ? (
							<GlassView isInteractive style={styles.jumpFace}>
								<JumpLabel />
							</GlassView>
						) : (
							<View
								style={[
									styles.jumpFace,
									styles.jumpOpaque,
									{ backgroundColor: palette.card, borderColor: palette.separator },
								]}
							>
								<JumpLabel />
							</View>
						)}
					</Pressable>
				)}
				<Composer
					bottomInset={measuredInset}
					ref={composerRef}
					onLayout={(event) => {
						onComposerLayout(event);
						setComposerHeight(event.nativeEvent.layout.height);
					}}
					text={draft}
					onChangeText={changeDraft}
					busy={busy}
					disabled={!composer.enabled}
					placeholder={composer.placeholder}
					onSend={conversation.send}
					onStop={conversation.stop}
				/>
			</KeyboardStickyView>
		</View>
	);
}

/**
 * The conversation's own actions in its header. On iOS: a fresh conversation beside a menu holding
 * deletion, where a lone destructive button beside the title would be easy to hit. Android's app bar
 * keeps deletion as a text action.
 */
function ConversationActions({ onNew, onDelete }: { onNew: () => void; onDelete: () => void }) {
	if (Platform.OS !== "ios") {
		return <HeaderAction symbol="trash" label="Delete" onPress={onDelete} />;
	}
	return (
		<Stack.Toolbar placement="right">
			<Stack.Toolbar.Button
				icon="square.and.pencil"
				accessibilityLabel="New conversation"
				onPress={onNew}
			/>
			<Stack.Toolbar.Menu icon="ellipsis" accessibilityLabel="More">
				<Stack.Toolbar.MenuAction icon="trash" destructive onPress={onDelete}>
					Delete conversation
				</Stack.Toolbar.MenuAction>
			</Stack.Toolbar.Menu>
		</Stack.Toolbar>
	);
}

/** Glass where the system has it, as for the composer it rides on. */
const GLASS = isLiquidGlassAvailable();

function JumpLabel() {
	return (
		<AppText variant="footnote" weight="semibold" tone="accent">
			Jump to latest
		</AppText>
	);
}

const styles = StyleSheet.create({
	screen: { flex: 1 },
	dock: { position: "absolute", left: 0, right: 0, bottom: 0 },
	jump: {
		alignSelf: "center",
		marginBottom: space.sm,
	},
	jumpFace: {
		paddingHorizontal: space.lg,
		paddingVertical: space.sm,
		borderRadius: radius.pill,
	},
	jumpOpaque: {
		borderWidth: StyleSheet.hairlineWidth,
		boxShadow: "0 2px 8px rgba(0, 0, 0, 0.12)",
	},
});
