import type { ReactNode } from "react";
import { StyleSheet, View } from "react-native";

import { AppText } from "./AppText";
import { Button } from "./Button";
import { StateView } from "./StateView";
import { radius, space, usePalette } from "./theme";

export interface QueryLike<T> {
	data: T | undefined;
	isError: boolean;
	isPending: boolean;
	refetch: () => unknown;
}

export interface QueryStatesProps<T> {
	query: QueryLike<T>;
	loadingLabel: string;
	errorTitle: string;
	errorMessage?: string;
	/** Shown instead of `children` when the answer is genuinely empty. */
	empty?: { when: (data: T) => boolean; view: ReactNode };
	children: (data: T) => ReactNode;
}

/**
 * The four answers a loaded list can give, never confused with each other: still loading, failed with
 * nothing to show, genuinely empty, and loaded. A refresh that fails over data already on screen keeps
 * the data and says it could not be refreshed, rather than presenting it as current or hiding it.
 */
export function QueryStates<T>({
	query,
	loadingLabel,
	errorTitle,
	errorMessage = "Check your connection and try again.",
	empty,
	children,
}: QueryStatesProps<T>): React.JSX.Element {
	const retry = () => {
		void query.refetch();
	};
	if (query.data === undefined) {
		if (query.isError) {
			return <StateView state="error" title={errorTitle} message={errorMessage} onRetry={retry} />;
		}
		return <StateView state="loading" label={loadingLabel} />;
	}
	const content = empty?.when(query.data) === true ? empty.view : children(query.data);
	if (!query.isError) {
		return <View style={styles.stale}>{content}</View>;
	}
	return (
		<View style={styles.stale}>
			<StaleBanner onRetry={retry} />
			{content}
		</View>
	);
}

/** Says that what is on screen could not be refreshed, over the data that loaded earlier. */
export function StaleBanner({ onRetry }: { onRetry: () => void }) {
	const palette = usePalette();
	return (
		<View
			style={[styles.banner, { backgroundColor: palette.fill }]}
			accessibilityLiveRegion="polite"
		>
			<AppText variant="subheadline" style={styles.bannerText}>
				Could not refresh. This is what loaded earlier.
			</AppText>
			<Button title="Retry" variant="secondary" onPress={onRetry} />
		</View>
	);
}

const styles = StyleSheet.create({
	stale: { gap: space.lg },
	banner: {
		flexDirection: "row",
		alignItems: "center",
		gap: space.md,
		padding: space.md,
		borderRadius: radius.control,
		borderCurve: "continuous",
	},
	bannerText: { flex: 1 },
});
