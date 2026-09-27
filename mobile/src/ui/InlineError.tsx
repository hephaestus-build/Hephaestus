import { StyleSheet, View } from "react-native";

import { AppText } from "./AppText";
import { Button } from "./Button";
import { space } from "./theme";

export interface InlineErrorProps {
	message: string;
	/** Repeats the action that failed — not a reload that would leave it undone. */
	onRetry?: () => void;
	retrying?: boolean;
	testID?: string;
}

/** A failed action, said where it happened, with the same action offered again. */
export function InlineError({ message, onRetry, retrying = false, testID }: InlineErrorProps) {
	return (
		<View style={styles.error} accessibilityLiveRegion="assertive" testID={testID}>
			<AppText tone="danger">{message}</AppText>
			{onRetry === undefined ? null : (
				<Button title="Try again" variant="secondary" pending={retrying} onPress={onRetry} />
			)}
		</View>
	);
}

const styles = StyleSheet.create({
	error: { gap: space.sm },
});
