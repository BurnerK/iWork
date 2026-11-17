package io.iwork.identity;

import java.io.Serializable;

/** Identity snapshot; IdentityService revalidates it against the live account. */
public record Caller(
        String id,
        String username,
        String displayName,
        String role,
        boolean mustChangePassword,
        long authVersion)
        implements Serializable {}
