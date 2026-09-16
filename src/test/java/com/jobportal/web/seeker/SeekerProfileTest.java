package com.jobportal.web.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.SeekerProfile;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.support.TestFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.ResultActions;

// Seeker profile and resume, S-F4 + S-D3 (Section 6.4). Neha starts at 0% (empty
// profile), Arjun at 60% and Priya at 100% (Section 13.3).
class SeekerProfileTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private SeekerProfileRepository seekerProfileRepository;

    // AC-S-F4-1: Neha saves skills "Java, Spring Boot"; the flash appears and
    // completeness goes from 0% (skills weight 20) to 20%.
    @Test
    void profileUpdateConfirmed() throws Exception {
        UserDetails neha = userDetailsService.loadUserByUsername("neha@demo.local");

        mockMvc.perform(post("/seeker/profile").with(user(neha)).with(csrf())
                        .param("fullName", "Neha Verma")
                        .param("email", "neha@demo.local")
                        .param("skills", "Java, Spring Boot")
                        .param("experienceYears", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/profile"))
                .andExpect(flash().attribute("success", "Profile updated successfully."));

        SeekerProfile profile = seekerProfileRepository.findByUser_Id(data.userId("neha@demo.local")).orElseThrow();
        assertThat(profile.getSkills()).isEqualTo("Java, Spring Boot");

        mockMvc.perform(get("/seeker/profile").with(user(neha)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("20%")));
    }

    // AC-S-F4-2: a valid PDF upload shows the success flash, is served back as
    // application/pdf, and a later wrong-type upload is rejected while the resume from the
    // first (successful) upload is kept.
    @Test
    void resumeUploadValidatedAndOldKeptOnError() throws Exception {
        UserDetails rohan = userDetailsService.loadUserByUsername("rohan@demo.local");
        MockMultipartFile goodPdf = new MockMultipartFile("resumeFile", "rohan_v2.pdf", "application/pdf", TestFiles.pdfBytes());

        mockMvc.perform(multipart("/seeker/profile/resume").file(goodPdf).with(user(rohan)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/profile"))
                .andExpect(flash().attribute("success", "Resume uploaded successfully."));

        mockMvc.perform(get("/seeker/profile/resume").with(user(rohan)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));

        MockMultipartFile badFile = new MockMultipartFile("resumeFile", "photo.png", "image/png", TestFiles.fakeContent());
        mockMvc.perform(multipart("/seeker/profile/resume").file(badFile).with(user(rohan)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Only PDF, DOC or DOCX files are allowed.")));

        SeekerProfile profile = seekerProfileRepository.findByUser_Id(data.userId("rohan@demo.local")).orElseThrow();
        assertThat(profile.getResumeOriginalName()).isEqualTo("rohan_v2.pdf");
    }

    // AC-S-F4-3: changing to an email already in use shows the duplicate error; changing
    // to a genuinely new one logs Priya out with the emailChanged message, and the new
    // address works for the next login (same pattern as EmployerProfileTest).
    @Test
    void emailChangeForcesRelogin() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        mockMvc.perform(post("/seeker/profile").with(user(priya)).with(csrf())
                        .param("fullName", "Priya Sharma")
                        .param("email", "arjun@demo.local")
                        .param("phone", "9876500001")
                        .param("location", "Pune")
                        .param("headline", "Java backend developer")
                        .param("skills", "Java, Spring Boot, SQL, Git")
                        .param("experienceYears", "2")
                        .param("education", "B.E. Computer Engineering, Pune, 2024")
                        .param("about", "I build REST APIs with Spring Boot and enjoy clean, tested code."))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An account with this email already exists.")));

        mockMvc.perform(post("/seeker/profile").with(user(priya)).with(csrf())
                        .param("fullName", "Priya Sharma")
                        .param("email", "priya.sharma@demo.local")
                        .param("phone", "9876500001")
                        .param("location", "Pune")
                        .param("headline", "Java backend developer")
                        .param("skills", "Java, Spring Boot, SQL, Git")
                        .param("experienceYears", "2")
                        .param("education", "B.E. Computer Engineering, Pune, 2024")
                        .param("about", "I build REST APIs with Spring Boot and enjoy clean, tested code."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?emailChanged"));

        login("priya.sharma@demo.local", "Seeker@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/dashboard"));
    }

    // AC-S-D3-1: Priya's profile page shows 100% and her resume card; Arjun's shows 60%
    // with the hint "Add a headline so employers know what you do." (headline is the
    // first missing field in weight order after his resume and skills, both present).
    @Test
    void completenessComputed() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        mockMvc.perform(get("/seeker/profile").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("100%")))
                .andExpect(content().string(containsString("Priya_Sharma_Resume.pdf")));

        UserDetails arjun = userDetailsService.loadUserByUsername("arjun@demo.local");
        mockMvc.perform(get("/seeker/profile").with(user(arjun)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("60%")))
                .andExpect(content().string(containsString("Add a headline so employers know what you do.")));
    }

    private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(session)
                .with(csrf()));
    }
}
