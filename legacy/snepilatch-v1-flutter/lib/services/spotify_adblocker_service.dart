class SpotifyAdBlockerService {
  // JavaScript functions are loaded from assets/js/spotify-adblocker.js
  // Functions injected into window:
  // - window.spotifyAdBlocker (object with methods)
  // - window.getAdBlockerStatus() - Returns ad blocker status as JSON

  // Scripts to control the ad blocker
  static const String enableAdBlockerScript = 'window.spotifyAdBlocker && window.spotifyAdBlocker.enable();';
  static const String disableAdBlockerScript = 'window.spotifyAdBlocker && window.spotifyAdBlocker.disable();';
  static const String getStatusScript = 'window.getAdBlockerStatus ? window.getAdBlockerStatus() : "{}";';
  static const String enableDebugScript = 'window.spotifyAdBlocker && window.spotifyAdBlocker.setDebug(true);';
  static const String disableDebugScript = 'window.spotifyAdBlocker && window.spotifyAdBlocker.setDebug(false);';
  static const String forceRemoveAdsScript = 'window.spotifyAdBlocker && window.spotifyAdBlocker.forceRemoveAds();';
}