package com.jobportal.web.common;

import com.jobportal.security.AppUserDetails;
import com.jobportal.security.RoleRoutes;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    // GET /dashboard: sends a logged-in user to their own role dashboard (Section 4.4, 6.1
    // P-4). Used by the post-login redirect and the navbar's "My dashboard" link. Only
    // reachable while authenticated - SecurityConfig sends anonymous requests to /login.
    @GetMapping("/dashboard")
    public ResponseEntity<Void> dashboard(@AuthenticationPrincipal AppUserDetails me) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(RoleRoutes.dashboardUrl(me.getRole())))
                .build();
    }

    // GET /: logged-in users go straight to their dashboard; everyone else sees a small
    // placeholder page inline (no template exists for it yet).
    // M2 replaces this with the real landing page (P-1: hero, search, latest jobs, CTAs).
    @GetMapping("/")
    public ResponseEntity<String> index(@AuthenticationPrincipal AppUserDetails me) {
        if (me != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/dashboard")).build();
        }
        String placeholder = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>JobPortal</title></head><body>"
                + "<h1>JobPortal</h1>"
                + "<p>The public landing page is not built yet.</p>"
                + "<p><a href=\"/login\">Log in</a> &middot; <a href=\"/register\">Register</a></p>"
                + "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(placeholder);
    }
}
