package com.jobportal.web.advice;

import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.service.SettingsService;
import com.jobportal.web.support.SafeRedirects;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Deliberately a PLAIN, unscoped @ControllerAdvice - see Section 7.3. MaxUploadSizeExceededException
// is raised by DispatcherServlet.checkMultipart(...) before handler mapping (handler ==
// null), and an advice carrying any selector (basePackages, annotations, assignableTypes)
// is skipped for that case. GlobalModelAttributes carries the basePackages selector;
// this class must not.
//
// No catch-all @ExceptionHandler(Exception.class) here on purpose: that would turn 403
// and 404 responses into 500s.
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SettingsService settingsService;

    public GlobalExceptionHandler(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ModelAndView handleNotFound(ResourceNotFoundException ex) {
        return new ModelAndView("error/404", HttpStatus.NOT_FOUND);
    }

    // A non-numeric path variable, e.g. /jobs/abc (query parameters never reach this
    // handler, Section 7.9: they are bound as String and parsed leniently in services).
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return new ModelAndView("error/404", HttpStatus.NOT_FOUND);
    }

    // Only reached for a simple POST button that does not catch this itself; form
    // controllers that need a field-level error catch BusinessRuleException before it
    // gets here (Section 7.2).
    @ExceptionHandler(BusinessRuleException.class)
    public String handleBusinessRule(BusinessRuleException ex, HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", ex.getMessage());
        return "redirect:" + SafeRedirects.backOrDashboard(request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleUploadTooLarge(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        int maxMb = settingsService.get().getMaxResumeSizeMb();
        redirectAttributes.addFlashAttribute("error",
                "The file is too large. The maximum resume size is " + maxMb + " MB.");
        return "redirect:" + SafeRedirects.backOrDashboard(request);
    }

    // A missed ownership/uniqueness check at the database level (Section 5.8 fallback).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        log.error("Data integrity violation", ex);
        redirectAttributes.addFlashAttribute("error", "The change could not be saved because related data exists.");
        return "redirect:" + SafeRedirects.backOrDashboard(request);
    }
}
