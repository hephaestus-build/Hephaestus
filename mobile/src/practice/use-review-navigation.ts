import { useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";

/** Both preview and paged history open the same evidence in their own native stack. */
export function useReviewNavigation(groupSlug: string, practiceSlug?: string) {
	const router = useRouter();
	const params = { groupSlug, ...(practiceSlug === undefined ? {} : { practiceSlug }) };
	return {
		onObservationPress: (observationId: string) =>
			router.push({
				pathname: "/practice/observation/[observationId]",
				params: { ...params, observationId },
			}),
		onAllHistory: () => router.push({ pathname: "/practice/history/[groupSlug]", params }),
		onOpenWork: (url: string) => {
			void WebBrowser.openBrowserAsync(url);
		},
	};
}
