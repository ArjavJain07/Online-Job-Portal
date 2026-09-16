/* ============================================================================
   Job Portal - Forms Progressive Enhancement
   Provides: confirm dialogs, submit button disabling, character counters,
   file size hints, and auto-dismiss alerts.
   ============================================================================ */

(function () {
  'use strict';

  /* ========================================================================
     Confirmation Dialog Handler
     ======================================================================== */
  document.addEventListener('submit', function (e) {
    const form = e.target;
    const confirmMessage = form.getAttribute('data-confirm');

    if (confirmMessage && !confirm(confirmMessage)) {
      e.preventDefault();
      return false;
    }
  });

  // Also handle individual button confirm dialogs
  document.addEventListener('click', function (e) {
    const button = e.target.closest('button[data-confirm], [type="submit"][data-confirm]');
    if (!button) return;

    const message = button.getAttribute('data-confirm');
    if (message && !confirm(message)) {
      e.preventDefault();
      e.stopPropagation();
      return false;
    }
  }, true); // use capture phase to run before form submission

  /* ========================================================================
     Submit Button Disabler
     ======================================================================== */
  document.addEventListener('submit', function (e) {
    const form = e.target;
    const submitButtons = form.querySelectorAll('button[type="submit"], input[type="submit"]');

    submitButtons.forEach(function (button) {
      button.setAttribute('disabled', 'disabled');
      const originalText = button.textContent;
      button.dataset.originalText = originalText;
      // Optional: show loading state
      // button.textContent = 'Processing...';
    });
  });

  /* ========================================================================
     Character Counter
     Syntax: <textarea data-maxlength="300" data-counter="id">
             <span id="id" class="char-counter">0 / 300</span>
     ======================================================================== */
  function updateCharCounter(input) {
    const counterId = input.getAttribute('data-counter');
    if (!counterId) return;

    const counter = document.getElementById(counterId);
    if (!counter) return;

    const maxLength = parseInt(input.getAttribute('data-maxlength'), 10) || input.maxLength;
    const current = input.value.length;
    const percent = maxLength ? (current / maxLength) * 100 : 0;

    counter.textContent = current + ' / ' + maxLength;

    // Add warning class when nearing limit (>80%)
    if (percent > 80) {
      counter.classList.add('warning');
    } else {
      counter.classList.remove('warning');
    }
  }

  // Initialize counters on page load
  document.querySelectorAll('[data-counter]').forEach(function (input) {
    updateCharCounter(input);
    input.addEventListener('input', function () {
      updateCharCounter(this);
    });
  });

  /* ========================================================================
     File Size Hint
     Syntax: <input type="file" data-max-size-mb="5" data-size-hint="id">
             <span id="id" class="file-size-hint"></span>
     ======================================================================== */
  function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
  }

  function updateFileSizeHint(input) {
    const hintId = input.getAttribute('data-size-hint');
    if (!hintId) return;

    const hint = document.getElementById(hintId);
    if (!hint) return;

    if (input.files.length === 0) {
      hint.textContent = '';
      return;
    }

    const file = input.files[0];
    const maxSizeMb = parseInt(input.getAttribute('data-max-size-mb'), 10);
    const maxSizeBytes = maxSizeMb * 1024 * 1024;
    const fileSize = file.size;
    const humanSize = formatFileSize(fileSize);

    let message = 'Size: ' + humanSize;
    if (maxSizeMb && fileSize > maxSizeBytes) {
      message += ' (exceeds ' + maxSizeMb + ' MB limit)';
      hint.classList.add('text-danger', 'fw-bold');
    } else {
      hint.classList.remove('text-danger', 'fw-bold');
    }

    hint.textContent = message;
  }

  document.querySelectorAll('input[type="file"][data-size-hint]').forEach(function (input) {
    input.addEventListener('change', function () {
      updateFileSizeHint(this);
    });
  });

  /* ========================================================================
     Auto-Dismiss Flash Alerts
     Dismisses alerts automatically after 5 seconds if they have
     data-auto-dismiss="true" attribute.
     ======================================================================== */
  function autoDismissAlert(alertElement, delay) {
    delay = delay || 5000; // default 5 seconds

    setTimeout(function () {
      const closeButton = alertElement.querySelector('.btn-close');
      if (closeButton) {
        closeButton.click();
      } else {
        // Fallback: manually remove if no close button
        alertElement.style.transition = 'opacity 0.3s ease-out';
        alertElement.style.opacity = '0';
        setTimeout(function () {
          alertElement.remove();
        }, 300);
      }
    }, delay);
  }

  // Find and auto-dismiss alerts marked with data-auto-dismiss
  document.querySelectorAll('.alert[data-auto-dismiss="true"]').forEach(function (alert) {
    autoDismissAlert(alert);
  });

  /* ========================================================================
     Enhance Bootstrap Alerts with Close Handlers
     ======================================================================== */
  document.querySelectorAll('.alert-dismissible').forEach(function (alert) {
    const closeButton = alert.querySelector('.btn-close');
    if (closeButton) {
      closeButton.addEventListener('click', function () {
        alert.style.transition = 'opacity 0.2s ease-out';
        alert.style.opacity = '0';
        setTimeout(function () {
          alert.remove();
        }, 200);
      });
    }
  });

  /* ========================================================================
     Demo Account Auto-Fill (Login Page)
     Syntax: <button type="button" data-fill-login="email|password">
     ======================================================================== */
  document.addEventListener('click', function (e) {
    const button = e.target.closest('button[data-fill-login]');
    if (!button) return;

    const creds = button.getAttribute('data-fill-login').split('|');
    const emailInput = document.querySelector('input[name="email"], input[type="email"]');
    const passwordInput = document.querySelector('input[name="password"], input[type="password"]');

    if (emailInput && creds[0]) {
      emailInput.value = creds[0];
    }
    if (passwordInput && creds[1]) {
      passwordInput.value = creds[1];
    }

    // Say what happened. Filling two fields further up the page is easy to miss,
    // especially once the demo list is scrolled into view.
    const status = document.getElementById('demo-fill-status');
    if (status) {
      const who = button.getAttribute('data-fill-name') || creds[0];
      status.textContent = 'Filled in ' + who + '. Press Log in to continue.';
      status.hidden = false;
    }

    // Close the demo list again and put the cursor on the button they need next.
    const panel = document.getElementById('demoAccounts');
    if (panel && window.bootstrap && window.bootstrap.Collapse) {
      window.bootstrap.Collapse.getOrCreateInstance(panel).hide();
    }
    const submit = document.querySelector('form[action$="/login"] button[type="submit"]');
    if (submit) {
      submit.scrollIntoView({ block: 'center', behavior: 'smooth' });
      submit.focus();
    }
  });

  /* ========================================================================
     Resume Choice Toggle (Application Form)
     Shows/hides upload or attached resume options based on radio selection.
     Syntax: <input type="radio" name="resumeChoice" value="upload" data-toggle="#resumeUpload">
     ======================================================================== */
  document.querySelectorAll('input[name="resumeChoice"]').forEach(function (radio) {
    radio.addEventListener('change', function () {
      // Hide all target elements
      document.querySelectorAll('[data-resume-choice-target]').forEach(function (el) {
        el.style.display = 'none';
      });

      // Show the one that matches the selected radio's data-toggle value
      const target = this.getAttribute('data-toggle');
      if (target) {
        const el = document.querySelector(target);
        if (el) {
          el.style.display = 'block';
        }
      }
    });

    // Trigger change on page load to set initial state
    if (radio.checked) {
      radio.dispatchEvent(new Event('change'));
    }
  });

})();
