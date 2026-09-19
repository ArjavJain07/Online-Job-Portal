package com.jobportal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// How long an emailed password-reset link stays usable (Section 16 #1, hard requirement
// 4's "expiry"). A ConfigurationProperties record of its own, the same shape as
// LockoutProperties and for a related reason: this number is part of the app's security
// posture - a longer expiry is a longer window in which a stolen or intercepted email
// stays dangerous - not a product knob like the ten SystemSettings fields of Section 7.5,
// so changing it needs access to the deployment rather than a form at /admin/settings.
//
// Registered on WebMvcConfig alongside AppProperties (see that class's comment), not on
// SecurityConfig next to LockoutProperties: unlike the lockout, nothing here is wired into
// the authentication provider itself, so it has no structural reason to live there.
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(

        // 60 minutes: long enough that a real user does not have to drop what they are
        // doing the moment the email arrives, short enough that a token sitting unread in
        // an old inbox is not a standing risk for the rest of the account's life.
        @DefaultValue("60") int tokenExpiryMinutes) {

    // Defaults are also written out in application.properties (LockoutProperties' own
    // comment explains why: visible where an operator looks for them); this guard only
    // catches a nonsense override such as app.password-reset.token-expiry-minutes=0,
    // which would make every emailed link dead on arrival.
    public PasswordResetProperties {
        if (tokenExpiryMinutes < 1) {
            throw new IllegalArgumentException("app.password-reset.token-expiry-minutes must be at least 1");
        }
    }
}
