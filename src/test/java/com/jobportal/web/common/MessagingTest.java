package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.User;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Employer-candidate messaging (Section 6.5.1, E-F3/E-D3). A1-A6, A14, A15 are Acme's
// applications, A7 is Globex's (Section 13.5); MSG1-MSG8 are the seeded messages (13.6).
class MessagingTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private UserRepository userRepository;

    // AC-E-F3-1: Acme messages Sneha on A4 (her fresh, un-replied Java Developer
    // application); the flash names her, the message shows "Sent", her unread count goes
    // up by one, and once she opens the thread Acme sees "Read" instead. No activity log
    // entry may ever contain the message body (Section 6.5.1 "Admin").
    @Test
    void employerMessageDeliveredUnreadThenRead() throws Exception {
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        UserDetails sneha = userDetailsService.loadUserByUsername("sneha@demo.local");
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer");
        Long snehaId = data.userId("sneha@demo.local");
        String messageBody = "Can you join an interview on Monday at 11am?";
        long snehaUnreadBefore = messageRepository.countByRecipient_IdAndReadAtIsNull(snehaId);

        mockMvc.perform(post("/employer/messages/{id}", a4Id).with(user(acme)).with(csrf())
                        .param("body", messageBody))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Message sent to Sneha Iyer."));

        assertThat(messageRepository.countByRecipient_IdAndReadAtIsNull(snehaId)).isEqualTo(snehaUnreadBefore + 1);

        mockMvc.perform(get("/employer/messages/{id}", a4Id).with(user(acme)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sent")));

        // Opening the thread as Sneha marks Acme's message read (Section 6.5.1 "Read
        // receipts"), before her own copy of the page is even built (7.10).
        mockMvc.perform(get("/seeker/messages/{id}", a4Id).with(user(sneha)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/employer/messages/{id}", a4Id).with(user(acme)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Read")));

        assertThat(activityLogRepository.findAll())
                .noneMatch(l -> l.getDescription() != null && l.getDescription().contains(messageBody));
    }

    // AC-E-F3-2 (wait-for-employer rule): Arjun cannot open A3 (no employer message on it
    // yet) with a reply; once Acme sends the first message, he can.
    @Test
    void seekerCanReplyOnlyAfterEmployer() throws Exception {
        UserDetails arjun = userDetailsService.loadUserByUsername("arjun@demo.local");
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        Long a3Id = data.applicationId("arjun@demo.local", "Java Developer");

        mockMvc.perform(post("/seeker/messages/{id}", a3Id).with(user(arjun)).with(csrf())
                        .param("body", "Any update on my application?"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "You can reply once the employer has messaged you."));

        mockMvc.perform(post("/employer/messages/{id}", a3Id).with(user(acme)).with(csrf())
                        .param("body", "Thanks for applying, we are reviewing it."))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Message sent to Arjun Mehta."));

        mockMvc.perform(post("/seeker/messages/{id}", a3Id).with(user(arjun)).with(csrf())
                        .param("body", "Thank you, looking forward to hearing back."))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Message sent to Acme Technologies."));
    }

    // AC-E-F3-2 (blocked-when rules): Rohan posting on A8 (withdrawn) gets the withdrawn
    // sentence; a deactivated account blocks messaging even on an otherwise active
    // application, and takes priority over the other rules (Section 6.5.1 "Check order").
    @Test
    void withdrawnOrDeactivatedBlocksMessaging() throws Exception {
        UserDetails rohan = userDetailsService.loadUserByUsername("rohan@demo.local");
        Long a8Id = data.applicationId("rohan@demo.local", "Data Analyst");

        mockMvc.perform(post("/seeker/messages/{id}", a8Id).with(user(rohan)).with(csrf())
                        .param("body", "Is this still open?"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Messaging is closed because this application was withdrawn."));

        // A1 (Priya/Java Developer, INTERVIEW - very much active) becomes blocked purely
        // because Priya's account is deactivated, ahead of any other rule.
        User priya = userRepository.findByEmail("priya@demo.local").orElseThrow();
        priya.setEnabled(false);
        userRepository.save(priya);
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        Long a1Id = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/messages/{id}", a1Id).with(user(acme)).with(csrf())
                        .param("body", "Following up on your interview."))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Messaging is unavailable because this account is deactivated."));
    }

    // AC-E-F3-2: Acme cannot reach A7, which belongs to Globex (Section 4.5 ownership).
    @Test
    void cannotMessageOtherEmployersApplicant() throws Exception {
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        Long a7Id = data.applicationId("arjun@demo.local", "Data Analyst");

        mockMvc.perform(get("/employer/messages/{id}", a7Id).with(user(acme)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/employer/messages/{id}", a7Id).with(user(acme)).with(csrf())
                        .param("body", "Hello"))
                .andExpect(status().isNotFound());
    }

    // AC-E-F3-2: a blank body re-renders the thread page with the field error (a
    // validation failure, so it stays a 200, never a redirect - Section 7.2).
    @Test
    void blankBodyRejected() throws Exception {
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        Long a1Id = data.applicationId("priya@demo.local", "Java Developer");
        long messageCountBefore = messageRepository.count();

        // th:errors HTML-escapes the apostrophe (renders "can&#39;t", Section 11.3
        // contract item 9), so the check avoids it - the same pattern
        // EmployerApplicationTest#shortlistWithNoteVisibleToSeeker follows for its own
        // apostrophe-bearing text.
        mockMvc.perform(post("/employer/messages/{id}", a1Id).with(user(acme)).with(csrf())
                        .param("body", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("be empty.")));

        assertThat(messageRepository.count()).isEqualTo(messageCountBefore);
    }

    // AC-E-D3-1: Acme's inbox lists threads for Priya Sharma (Java Developer), Rohan Das
    // (Java Developer) and Sneha Iyer (Frontend Developer) in that order (newest last
    // message first: MSG3 1 day ago, MSG4 3 days ago, MSG8 4 days ago), with an unread
    // badge of 1 on Sneha's row (MSG8, sent to Acme, still unread); the compose select
    // lists exactly Acme's 7 non-withdrawn applications (A15 is withdrawn).
    @Test
    void inboxOrderedWithUnreadCounts() throws Exception {
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");

        String inbox = mockMvc.perform(get("/employer/messages").with(user(acme)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int priyaIndex = inbox.indexOf("Priya Sharma");
        int rohanIndex = inbox.indexOf("Rohan Das");
        int snehaIndex = inbox.indexOf("Sneha Iyer");
        assertThat(priyaIndex).isPositive();
        assertThat(rohanIndex).isGreaterThan(priyaIndex);
        assertThat(snehaIndex).isGreaterThan(rohanIndex);

        // The unread badge sits right after Sneha's row content, ahead of Priya's/Rohan's
        // rows which carry no badge at all (Acme has no unread message on A1 or A2).
        String snehaRow = inbox.substring(snehaIndex, Math.min(inbox.length(), snehaIndex + 600));
        assertThat(snehaRow).contains("badge bg-danger", ">1<");

        String compose = mockMvc.perform(get("/employer/messages/new").with(user(acme)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String option : new String[] {"Priya Sharma: Java Developer", "Rohan Das: Java Developer",
                "Arjun Mehta: Java Developer", "Sneha Iyer: Java Developer", "Priya Sharma: Frontend Developer",
                "Sneha Iyer: Frontend Developer", "Priya Sharma: Python Backend Developer"}) {
            assertThat(compose).contains(option);
        }
        assertThat(compose).doesNotContain("Sneha Iyer: Python Backend Developer"); // A15, withdrawn
    }

    // Same compose page, checked in isolation for its own named test (Section 12.2):
    // exactly Acme's own, non-withdrawn applicants, never a foreign or withdrawn one.
    @Test
    void composeListsOwnActiveApplicants() throws Exception {
        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");

        String compose = mockMvc.perform(get("/employer/messages/new").with(user(acme)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(compose).contains("Priya Sharma: Java Developer");
        assertThat(compose).doesNotContain("Arjun Mehta: Data Analyst"); // A7, belongs to Globex
        assertThat(compose).doesNotContain("Sneha Iyer: Python Backend Developer"); // A15, withdrawn
    }
}
