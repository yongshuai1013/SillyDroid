(function() {
  const nativeApp = 'sillydroid_host';
  let hostPort = null;

  function compactError(error) {
    return String(error && error.message || error || 'unknown').replace(/\s+/g, '_').slice(0, 180);
  }

  function sendNative(action, payload) {
    return browser.runtime.sendNativeMessage(nativeApp, { action, payload }).catch(function() {});
  }

  function recordDiagnostic(payload) {
    sendNative('recordWebPerformanceDiagnostic', String(payload || ''));
  }

  function currentActiveTab() {
    if (!browser.tabs || typeof browser.tabs.query !== 'function') {
      return Promise.reject(new Error('tabs_query_unavailable'));
    }
    return browser.tabs.query({ active: true, currentWindow: true }).then(function(tabs) {
      const tab = tabs && tabs[0];
      if (!tab || typeof tab.id !== 'number') {
        throw new Error('active_tab_unavailable');
      }
      return tab;
    });
  }

  function setTabsZoom(percent, reason) {
    const sanitizedPercent = Math.max(50, Math.min(150, Math.round(Number(percent || 100) / 5) * 5));
    const factor = sanitizedPercent / 100;
    if (!browser.tabs || typeof browser.tabs.setZoom !== 'function') {
      recordDiagnostic(
        'event=gecko_tabs_zoom_unavailable reason=tabs_set_zoom_unavailable percent=' + sanitizedPercent
      );
      return;
    }
    currentActiveTab()
      .then(function(tab) {
        return browser.tabs.setZoom(tab.id, factor).then(function() {
          recordDiagnostic(
            'event=gecko_tabs_zoom_applied percent=' + sanitizedPercent +
              ' factor=' + factor +
              ' tabId=' + tab.id +
              ' reason=' + String(reason || 'host')
          );
        });
      })
      .catch(function(error) {
        recordDiagnostic(
          'event=gecko_tabs_zoom_failed percent=' + sanitizedPercent +
            ' factor=' + factor +
            ' reason=' + compactError(error)
        );
      });
  }

  function connectHostPort() {
    try {
      hostPort = browser.runtime.connectNative(nativeApp);
      hostPort.onMessage.addListener(function(message) {
        if (!message || message.action !== 'setTabsZoom') {
          return;
        }
        setTabsZoom(message.percent, message.reason);
      });
      hostPort.onDisconnect.addListener(function() {
        hostPort = null;
        setTimeout(connectHostPort, 1000);
      });
      recordDiagnostic('event=gecko_tabs_zoom_port_opened');
    } catch (error) {
      recordDiagnostic('event=gecko_tabs_zoom_port_failed reason=' + compactError(error));
      setTimeout(connectHostPort, 1000);
    }
  }

  connectHostPort();
})();
