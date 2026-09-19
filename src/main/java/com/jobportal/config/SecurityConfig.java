package com.jobportal.config;

import com.jobportal.security.LoginFailureHandler;
import com.jobportal.security.RoleBasedAuthenticationSuccessHandler;
import jakarta.servlet.DispatcherType;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// The URL rules, login/logout and CSRF setup for the whole app (Section 4.2). No
// @PreAuthorize anywhere: URL zones decide the role here, ownership is checked in the
// service layer (4.5).
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Spring Security 6.5: makes string patterns unambiguous even though the H2 console
    // adds a second servlet (M0 spike 4).
    @Bean
    PathPatternRequestMatcherBuilderFactoryBean requestMatcherBuilder() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
            RoleBasedAuthenticationSuccessHandler successHandler,
            LoginFailureHandler failureHandler,
            @Value("${spring.h2.console.enabled:false}") boolean h2Console) throws Exception {
        if (h2Console) {
            http.authorizeHttpRequests(auth -> auth.requestMatchers(PathRequest.toH2Console()).hasRole("ADMIN"))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(PathRequest.toH2Console()));
        }
        http
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/", "/jobs", "/jobs/*", "/companies", "/companies/*", "/login", "/register", "/register/**", "/error")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/employer/**").hasRole("EMPLOYER")
                        .requestMatchers("/seeker/**").hasRole("JOB_SEEKER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(loginRequiredEntryPoint()))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    // What an anonymous user gets when a URL needs login: the activity-feed script gets
    // 401 (so it can stop polling instead of following a redirect), everything else goes
    // to /login. Built by hand instead of defaultAuthenticationEntryPointFor because form
    // login registers its own default entry point later; see the note in Section 4.2.
    private AuthenticationEntryPoint loginRequiredEntryPoint() {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest"),
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        entryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"));
        return entryPoint;
    }
}
