import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";

import { personLevel } from "./activity-search";
import type { ActivityPeopleTableProps } from "./ActivityPeopleTable";

type PersonLink = ActivityPeopleTableProps["personLink"];

/** On workspace activity a name opens the person's level over the page. */
export const personLevelLink: PersonLink = ({ login }) => (
	<DetailStackLink entry={personLevel(login)} />
);

/**
 * On the public page a name leads to the person's page at the provider, which opens apart from the
 * page. The link says so, since its words are only the name.
 */
export const providerProfileLink: PersonLink = ({ name, htmlUrl }) => (
	<a
		href={htmlUrl}
		target="_blank"
		rel="noopener noreferrer"
		aria-label={`${name}, profile (opens in a new tab)`}
	/>
);
