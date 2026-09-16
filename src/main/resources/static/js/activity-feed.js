/* ============================================================================
   Job Portal - Live Activity Feed (Section 7.7)
   Polls GET /admin/activity/feed?afterId=... on a timer instead of using
   Server-Sent Events (see I-1/D-1 in the plan): plain request/response, no
   long-lived connection held per open tab. Only runs on the two pages that
   include this script and have a #activity-feed element in the page:
   admin/activity.html and admin/dashboard.html.
   ============================================================================ */
(function () {
  'use strict';

  const feed = document.getElementById('activity-feed');
  if (!feed) return; // this page has no live widget - nothing to do

  const latestApplications = document.getElementById('latest-applications'); // optional panel
  const newEventCount = document.getElementById('new-event-count'); // optional counter, admin/activity only
  let lastId = Number(feed.dataset.lastId);
  let received = 0;
  const intervalMs = Number(feed.dataset.intervalMs);

  // Builds one <li> the same way the server already rendered the existing rows
  // (Thymeleaf's fragments/activity-feed): "16 Sep, 10:42 · description".
  // textContent only, never innerHTML, so a description can never inject markup.
  function addEventRow(list, event) {
    const li = document.createElement('li');
    li.className = 'feed-item feed-new';
    li.textContent = event.timeLabel + ' · ' + event.description;
    list.prepend(li);
  }

  function handle(event) {
    addEventRow(feed, event);
    if (latestApplications && event.type === 'APPLICATION_SUBMITTED') {
      addEventRow(latestApplications, event);
      while (latestApplications.children.length > 5) { // keep only the newest 5
        latestApplications.lastElementChild.remove();
      }
    }
  }

  async function poll() {
    if (document.hidden) {
      schedule(); // skip while the tab is hidden, try again next interval
      return;
    }
    try {
      const res = await fetch(feed.dataset.feedUrl + '?afterId=' + lastId, {
        headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      });
      const contentType = res.headers.get('content-type') || '';
      if (res.redirected || !res.ok || contentType.indexOf('application/json') === -1) {
        // A redirect or a non-JSON response means the admin is no longer signed
        // in: the session expired (401, thanks to the X-Requested-With header -
        // Section 4.2) or the account was deactivated meanwhile and the request
        // followed the login redirect. Either way, stop polling for good.
        stop(res.status === 401
          ? 'Your session has expired. Reload the page to log in again.'
          : 'Live updates stopped. Reload the page.');
        return; // no further polls
      }
      const events = await res.json();
      events.forEach(handle);
      if (newEventCount) {
        received += events.length;
        newEventCount.textContent = received; // "N new events since you opened this page"
      }
      if (events.length > 0) {
        lastId = events[events.length - 1].id;
      }
      if (events.length === 50) {
        poll(); // more may be waiting - fetch again right away instead of waiting
        return;
      }
    } catch (e) {
      // network blip: try again at the next interval
    }
    schedule();
  }

  function schedule() {
    setTimeout(poll, intervalMs); // next poll only starts after this one finishes
  }

  function stop(message) {
    const status = document.getElementById('feed-status');
    if (status) status.textContent = message;
    // no further setTimeout is scheduled here - polling simply ends
  }

  schedule();
})();
