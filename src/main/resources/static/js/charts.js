/* ============================================================================
   Job Portal - Chart.js Helper
   Renders Chart.js charts with Bootstrap colors and accessibility support.
   Provides: line, bar, groupedBar, doughnut, pie chart types.
   ============================================================================ */

if (typeof Chart === 'undefined') {
  console.error('Chart.js library not found. Include it before this script.');
}

window.JobPortalCharts = (function () {
  'use strict';

  /* ========================================================================
     Color Palette (Bootstrap 5 Colors)
     ======================================================================== */
  const colors = {
    primary: '#0d6efd',
    success: '#198754',
    danger: '#dc3545',
    warning: '#ffc107',
    info: '#0dcaf0',
    dark: '#212529',
    secondary: '#6c757d',
  };

  // For multi-series charts, cycle through these colors
  const seriesColors = [
    colors.primary,
    colors.info,
    colors.success,
    colors.danger,
    colors.warning,
    colors.dark,
    colors.secondary,
  ];

  /* ========================================================================
     Chart Options (Shared)
     ======================================================================== */

  function getSharedOptions() {
    return {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: true,
          labels: {
            font: { size: 12 },
            padding: 15,
            usePointStyle: true,
          },
        },
        filler: true,
      },
    };
  }

  function shouldReduceMotion() {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  function getAnimationOptions() {
    return shouldReduceMotion() ? { duration: 0 } : { duration: 750 };
  }

  /* ========================================================================
     Utility: Get Chart Data
     Gets data from data attribute or inline JSON block.
     Returns: { label, labels, values } or { label, series: [{ label, labels, values }] }
     ======================================================================== */

  function getChartData(element) {
    // Try data attribute first
    const dataAttr = element.getAttribute('data-chart');
    if (dataAttr) {
      try {
        return JSON.parse(dataAttr);
      } catch (e) {
        console.error('Invalid JSON in data-chart attribute:', e);
        return null;
      }
    }

    // Try inline JSON block (next <script> tag with type="application/json")
    let script = element.nextElementSibling;
    while (script) {
      if (script.tagName === 'SCRIPT' && script.type === 'application/json') {
        try {
          return JSON.parse(script.textContent);
        } catch (e) {
          console.error('Invalid JSON in chart data script:', e);
          return null;
        }
      }
      script = script.nextElementSibling;
    }

    return null;
  }

  /* ========================================================================
     Chart Type: Line
     ======================================================================== */

  function renderLine(canvasId, data) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) {
      console.warn('Canvas element #' + canvasId + ' not found.');
      return null;
    }

    if (!data || !data.labels || !data.values) {
      console.error('Invalid data for line chart');
      return null;
    }

    const ctx = canvas.getContext('2d');

    const chartConfig = {
      type: 'line',
      data: {
        labels: data.labels,
        datasets: [
          {
            label: data.label || 'Data',
            data: data.values,
            borderColor: colors.primary,
            backgroundColor: 'rgba(13, 110, 253, 0.1)',
            borderWidth: 2,
            fill: true,
            pointRadius: 4,
            pointBackgroundColor: colors.primary,
            pointBorderColor: '#fff',
            pointBorderWidth: 2,
            pointHoverRadius: 5,
            tension: 0.4,
          },
        ],
      },
      options: Object.assign({}, getSharedOptions(), {
        animation: getAnimationOptions(),
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(0, 0, 0, 0.05)' },
          },
          x: {
            grid: { display: false },
          },
        },
      }),
    };

    return new Chart(ctx, chartConfig);
  }

  /* ========================================================================
     Chart Type: Bar
     ======================================================================== */

  function renderBar(canvasId, data, horizontal) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) {
      console.warn('Canvas element #' + canvasId + ' not found.');
      return null;
    }

    if (!data || !data.labels || !data.values) {
      console.error('Invalid data for bar chart');
      return null;
    }

    const ctx = canvas.getContext('2d');
    const isHorizontal = horizontal === true;

    const chartConfig = {
      type: isHorizontal ? 'barH' : 'bar',
      data: {
        labels: data.labels,
        datasets: [
          {
            label: data.label || 'Data',
            data: data.values,
            backgroundColor: colors.info,
            borderColor: colors.info,
            borderWidth: 0,
            borderRadius: 3,
          },
        ],
      },
      options: Object.assign({}, getSharedOptions(), {
        animation: getAnimationOptions(),
        indexAxis: isHorizontal ? 'y' : 'x',
        scales: {
          y: {
            grid: { color: 'rgba(0, 0, 0, 0.05)' },
            beginAtZero: true,
          },
          x: {
            grid: { display: !isHorizontal },
            beginAtZero: true,
          },
        },
      }),
    };

    // Chart.js v3+ uses 'bar' for both vertical and horizontal
    // Check if 'barH' exists, otherwise use 'bar' with indexAxis
    if (Chart.registry.getChart('barH') === undefined) {
      chartConfig.type = 'bar';
    }

    return new Chart(ctx, chartConfig);
  }

  /* ========================================================================
     Chart Type: Grouped Bar
     Renders multiple series as grouped bars.
     Data: { series: [{ label, labels, values }, ...] }
     ======================================================================== */

  function renderGroupedBar(canvasId, seriesList) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) {
      console.warn('Canvas element #' + canvasId + ' not found.');
      return null;
    }

    if (!seriesList || seriesList.length === 0) {
      console.error('Invalid data for grouped bar chart');
      return null;
    }

    const ctx = canvas.getContext('2d');

    // All series should have the same labels; use the first series' labels
    const labels = seriesList[0].labels || [];

    const datasets = seriesList.map(function (series, index) {
      return {
        label: series.label,
        data: series.values,
        backgroundColor: seriesColors[index % seriesColors.length],
        borderColor: seriesColors[index % seriesColors.length],
        borderWidth: 0,
        borderRadius: 3,
      };
    });

    const chartConfig = {
      type: 'bar',
      data: {
        labels: labels,
        datasets: datasets,
      },
      options: Object.assign({}, getSharedOptions(), {
        animation: getAnimationOptions(),
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(0, 0, 0, 0.05)' },
          },
          x: {
            grid: { display: false },
          },
        },
      }),
    };

    return new Chart(ctx, chartConfig);
  }

  /* ========================================================================
     Chart Type: Doughnut
     ======================================================================== */

  function renderDoughnut(canvasId, data) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) {
      console.warn('Canvas element #' + canvasId + ' not found.');
      return null;
    }

    if (!data || !data.labels || !data.values) {
      console.error('Invalid data for doughnut chart');
      return null;
    }

    const ctx = canvas.getContext('2d');

    const chartConfig = {
      type: 'doughnut',
      data: {
        labels: data.labels,
        datasets: [
          {
            data: data.values,
            backgroundColor: seriesColors.slice(0, data.values.length),
            borderColor: '#fff',
            borderWidth: 2,
          },
        ],
      },
      options: Object.assign({}, getSharedOptions(), {
        animation: getAnimationOptions(),
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              padding: 15,
            },
          },
        },
      }),
    };

    return new Chart(ctx, chartConfig);
  }

  /* ========================================================================
     Chart Type: Pie
     ======================================================================== */

  function renderPie(canvasId, data) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) {
      console.warn('Canvas element #' + canvasId + ' not found.');
      return null;
    }

    if (!data || !data.labels || !data.values) {
      console.error('Invalid data for pie chart');
      return null;
    }

    const ctx = canvas.getContext('2d');

    const chartConfig = {
      type: 'pie',
      data: {
        labels: data.labels,
        datasets: [
          {
            data: data.values,
            backgroundColor: seriesColors.slice(0, data.values.length),
            borderColor: '#fff',
            borderWidth: 2,
          },
        ],
      },
      options: Object.assign({}, getSharedOptions(), {
        animation: getAnimationOptions(),
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              padding: 15,
            },
          },
        },
      }),
    };

    return new Chart(ctx, chartConfig);
  }

  /* ========================================================================
     Public API
     ======================================================================== */

  return {
    line: function (canvasId, data) {
      const resolvedData = data || getChartData(document.getElementById(canvasId) || {});
      return renderLine(canvasId, resolvedData);
    },

    bar: function (canvasId, data, horizontal) {
      const resolvedData = data || getChartData(document.getElementById(canvasId) || {});
      return renderBar(canvasId, resolvedData, horizontal);
    },

    groupedBar: function (canvasId, seriesList) {
      if (!seriesList) {
        const container = document.getElementById(canvasId);
        if (container) {
          const dataAttr = container.getAttribute('data-chart');
          if (dataAttr) {
            try {
              const parsed = JSON.parse(dataAttr);
              seriesList = parsed.series || [parsed];
            } catch (e) {
              console.error('Invalid JSON in data-chart:', e);
            }
          }
        }
      }
      return renderGroupedBar(canvasId, seriesList);
    },

    doughnut: function (canvasId, data) {
      const resolvedData = data || getChartData(document.getElementById(canvasId) || {});
      return renderDoughnut(canvasId, resolvedData);
    },

    pie: function (canvasId, data) {
      const resolvedData = data || getChartData(document.getElementById(canvasId) || {});
      return renderPie(canvasId, resolvedData);
    },

    // Utility: get chart data from element
    getData: getChartData,
  };
})();
