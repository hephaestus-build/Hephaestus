/** A workspace member as the staff screen lists them. */
export interface TeamProjectPerson {
	accountId: number;
	displayName: string;
	/** Tells two members with one name apart before an admin grants one of them access. */
	login?: string;
	avatarUrl?: string;
}

/** A member's name, with their login when they have one. */
export function personLabel(person: TeamProjectPerson): string {
	return person.login === undefined
		? person.displayName
		: `${person.displayName} (${person.login})`;
}

/**
 * A team project as a workspace admin sees it: that it exists, its members and its counts. Never its messages,
 * decisions or versions.
 */
export interface TeamProjectRow {
	id: string;
	name: string;
	members: readonly TeamProjectPerson[];
	reviewCount: number;
}
