import type { TeamProjectPerson, TeamProjectRow } from "./team-projects";

/** Synthetic people only. */
export const ADA: TeamProjectPerson = { accountId: 1, displayName: "Ada Lovelace" };
export const ALAN: TeamProjectPerson = { accountId: 2, displayName: "Alan Turing" };
export const GRACE: TeamProjectPerson = { accountId: 3, displayName: "Grace Hopper" };

export const WAYFINDING: TeamProjectRow = {
	id: "c1000000-0000-4000-8000-000000000001",
	name: "Wayfinding",
	members: [ADA, ALAN],
	reviewCount: 2,
};

export const EMPTY_TEAM: TeamProjectRow = {
	id: "c1000000-0000-4000-8000-000000000002",
	name: "Canteen",
	members: [],
	reviewCount: 0,
};
