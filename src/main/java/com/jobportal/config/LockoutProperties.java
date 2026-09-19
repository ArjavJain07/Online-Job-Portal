package com.jobportal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// The two numbers behind the login lockout of Section 4.10, bound from app.lockout.* and
// registered by @EnableConfigurationProperties(LockoutProperties.class) on SecurityConfig
// (a record uses constructor binding, so it is not component-scanned).
//
// Why properties and NOT two more rows in SystemSettings, next to the ten admin-editable
// settings of 7.5:
//   - Every existing setting is a product knob (page size, maximum jobs per employer,
//     allowed resume types). Those change what the site does; this one decides how hard
//     it is to break into it. Security policy in this project lives in SecurityConfig and
//     in code (4.2), not behind a web form.
//   - An admin-editable threshold is itself an attack surface: one compromised admin
//     session could set maxAttempts to 999 through /admin/settings and switch the whole
//     defence off, with nothing but an activity-log line to show for it. Changing a
//     property needs access to the deployment (Render's environment or a redeploy), which
//     is a much higher bar than a form POST.
//   - The hosted profile can tighten the numbers per environment without a schema change,
//     which a single system_settings row cannot do.
// The cost of the choice: an operator cannot loosen the lockout during a live demo
// without a restart. Acceptable - a locked demo account clears itself after the cooldown.
@ConfigurationProperties(prefix = "app.lockout")
public record LockoutProperties(

        // Consecutive failures that lock the account. Five is the usual compromise: it
        // survives a user mistyping a password a few times, while a bot needs a fresh
        // cooldown for every five guesses.
        @DefaultValue("5") int maxAttempts,

        // How long the account stays locked. Long enough to make guessing pointless
        // (five tries per quarter hour), short enough that a real user locked out by a
        // forgotten password is not stuck for the evening - this app has no password
        // reset email (Section 16), so a permanent lock would need an admin.
        @DefaultValue("15") int cooldownMinutes) {

    // Defaults are also written out in application.properties, so the values are visible
    // where an operator looks for them; this guard only catches a nonsense override
    // (app.lockout.max-attempts=0 would lock every account on its first failed login).
    public LockoutProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.lockout.max-attempts must be at least 1");
        }
        if (cooldownMinutes < 1) {
            throw new IllegalArgumentException("app.lockout.cooldown-minutes must be at least 1");
        }
    }
}
