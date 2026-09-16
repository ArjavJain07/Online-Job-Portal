package com.jobportal.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Message composer, shared by every route that posts a message (Section 6.5.1, 6.6): the
// employer's "New message" compose page (POST /employer/messages, where applicationId IS
// submitted, from the select) and the reply box on both roles' thread pages and the
// embedded thread on both application-detail pages (POST .../messages/{applicationId},
// where applicationId comes from the path instead and this field is simply not present on
// that page's form at all).
public class MessageForm {

    // Deliberately carries no validation annotation of its own (Section 6.5.1 "Compose
    // page": "a required form field", enforced by the controller, not here) because this
    // one class is reused by pages where the field does not exist at all - a shared
    // @NotNull would wrongly reject every reply, since it would never be submitted there.
    // On the compose page, leaving the select on its blank first option already fails
    // Spring's own String-to-Long conversion with the framework's default "typeMismatch"
    // field error - the same accepted pattern ApplicationStatusForm's own class comment
    // documents for its required status select - so no extra annotation is needed to make
    // an empty selection a validation error on that one page.
    private Long applicationId;

    // The doubled apostrophe is deliberate, not a typo: Hibernate Validator's default
    // interpolator runs every constraint message through java.text.MessageFormat even
    // when it has no {n} parameters, and MessageFormat treats a single quote as an escape
    // character - a lone "can't" silently loses its apostrophe ("Message cant be empty."
    // at runtime) while "can''t" is MessageFormat's own escape for one literal apostrophe
    // (Section 6.5.1's exact wording is "Message can't be empty.", one apostrophe).
    @NotBlank(message = "Message can''t be empty.")
    @Size(max = 2000, message = "Message must be 2000 characters or fewer.")
    private String body;

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
