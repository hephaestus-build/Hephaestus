/** A workspace member as the staff screen lists them. */
export interface TeamProjectPerson {
	accountId: number;
	displayName: string;
	avatarUrl?: string;
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
