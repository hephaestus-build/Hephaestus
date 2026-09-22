/**
 * sessionStorage key for the one-shot "your account was scheduled for deletion" notice.
 *
 * The self-deletion flow signs the user out and does a full reload to the login page, so a toast
 * fired in the mutation's onSuccess would be destroyed by the reload. The Danger Zone sets this flag
 * just before logging out, and the login route reads-and-clears it to announce the real outcome.
 */
export const ACCOUNT_DELETED_NOTICE_KEY = "hephaestus-account-deleted-notice";
