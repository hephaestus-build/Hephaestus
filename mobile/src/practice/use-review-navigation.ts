import { useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";

/**
 * Both preview and paged history open the same evidence in their own native stack. An observation
 * needs only its id; the whole history keeps the group and the practice it is narrowed to.
 */
export function useReviewNavigation(groupSlug: string, practiceSlug?: string) {
	const router = useRouter();
	return {
		onObservationPress: (observationId: string) =>
			router.push({ pathname: "/practice/observation/[observationId]", params: { observationId } }),
		onAllHistory: () =>
			router.push({
				pathname: "/practice/history/[groupSlug]",
				params: { groupSlug, ...(practiceSlug === undefined ? {} : { practiceSlug }) },
			}),
		onOpenWork: (url: string) => {
			void WebBrowser.openBrowserAsync(url);
		},
	};
}
