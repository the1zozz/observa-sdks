package com.integralways.observa.spring;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The default with Spring Security: the authenticated user's name, or null for nobody or an anonymous visitor. */
final class SecurityUserResolver implements ObservaUserResolver {

    @Override
    public String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = auth.getName();
        return name == null || name.trim().isEmpty() ? null : name;
    }
}
