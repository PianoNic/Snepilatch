class SpotifyActionsService {
  // JavaScript functions are loaded from:
  // - assets/js/spotify-actions.js (playback controls)
  // - assets/js/spotify-playlist-controller.js (playlist management)

  // Action functions injected into window:
  // - window.spotifyPlay()
  // - window.spotifyPause()
  // - window.spotifyNext()
  // - window.spotifyPrevious()
  // - window.spotifyToggleShuffle()
  // - window.spotifyToggleRepeat()
  // - window.spotifyToggleLike()
  // - window.spotifySeek(percentage)
  // - window.spotifySearch(query)
  // - window.spotifyLogout()
  // - window.spotifyPlayTrackAtIndex(index)
  // - window.spotifyScrollPage(offset)
  // - window.spotifyOpenLikedSongs()

  // PlaylistController functions:
  // - window.PlaylistController (class instance)
  // - window.loadMoreTracks()
  // - window.getLoadedTracks()
  // - window.debugScrollInfo()
  // - window.resetLoadingState()

  // Playback control scripts that call the window functions
  static const String playScript = 'window.spotifyPlay();';
  static const String pauseScript = 'window.spotifyPause();';
  static const String nextScript = 'window.spotifyNext();';
  static const String previousScript = 'window.spotifyPrevious();';
  static const String toggleShuffleScript = 'window.spotifyToggleShuffle();';
  static const String toggleRepeatScript = 'window.spotifyToggleRepeat();';
  static const String toggleLikeScript = 'window.spotifyToggleLike();';
  static String seekToPositionScript(double percentage) => 'window.spotifySeek($percentage);';
  static String searchScript(String query) => 'window.spotifySearch("$query");';
  static const String searchResultsScript = 'window.getSearchResults();';
  static const String logoutScript = 'window.spotifyLogout();';
  static String playTrackAtIndexScript(int index) => 'window.spotifyPlayTrackAtIndex($index);';
  static const String scrapeSongsScript = 'window.getSongs();';
  static String scrollSpotifyPageScript(double offset) => 'window.spotifyScrollPage($offset);';
  static const String openLikedSongsScript = 'window.spotifyOpenLikedSongs();';

  // PlaylistController scripts
  static const String loadMoreSongsScript = 'window.loadMoreTracks();';
  static const String getLoadedTracksScript = 'window.getLoadedTracks();';
  static const String debugScrollScript = 'window.debugScrollInfo();';
  static const String resetLoadingStateScript = 'window.resetLoadingState();';

  // Now Playing View scripts
  static const String openNPVScript = 'window.spotifyToggleNPV && window.spotifyToggleNPV(true);';
  static const String closeNPVScript = 'window.spotifyToggleNPV && window.spotifyToggleNPV(false);';
  static const String toggleNPVScript = 'window.spotifyToggleNPV && window.spotifyToggleNPV();';
  static const String checkNPVStateScript = 'window.isNPVOpen ? window.isNPVOpen() : false;';
  static const String ensureNPVForVideoScript = 'window.ensureNPVOpenForVideo && window.ensureNPVOpenForVideo();';
}