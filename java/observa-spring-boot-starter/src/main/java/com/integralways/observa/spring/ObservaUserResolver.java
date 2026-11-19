package com.integralways.observa.spring;

/**
 * Who is signed in on the current thread, as the user id your apps pass to {@code observa.identify(…)}: errors that
 * name no person are linked to them. With Spring Security on the classpath, the starter uses the authenticated name;
 * define this bean when your user id is something else, for example the database id:
 * <pre>{@code
 * @Bean
 * ObservaUserResolver observaUser(UserRepository users) {
 *     return () -> {
 *         Authentication auth = SecurityContextHolder.getContext().getAuthentication();
 *         return auth == null ? null : users.findByUsername(auth.getName()).map(u -> u.getId().toString()).orElse(null);
 *     };
 * }
 * }</pre>
 * Return null when nobody is signed in. It must be quick and must not throw.
 */
@FunctionalInterface
public interface ObservaUserResolver {

    String currentUserId();
}
