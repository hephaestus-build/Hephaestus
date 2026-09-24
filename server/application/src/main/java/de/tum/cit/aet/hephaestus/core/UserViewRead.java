package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A handler that discloses a viewed user's private content: step-up gated and recorded before it runs. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequiresRecentSignIn
@Audited(ledger = AuditLedger.AUTH_EVENT, type = "USER_VIEW")
public @interface UserViewRead {}
