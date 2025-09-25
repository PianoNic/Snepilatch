class SpotifyActionsService {
  // Playback control scripts
  static const String playScript = '''
    (function() {
      const playButton = document.querySelector('[data-testid="control-button-playpause"]');
      if (playButton && playButton.getAttribute('aria-label')?.includes('Play')) {
        playButton.click();
      }
    })();
  ''';

  static const String pauseScript = '''
    (function() {
      const pauseButton = document.querySelector('[data-testid="control-button-playpause"]');
      if (pauseButton && pauseButton.getAttribute('aria-label')?.includes('Pause')) {
        pauseButton.click();
      }
    })();
  ''';

  static const String nextScript = '''
    (function() {
      const nextButton = document.querySelector('[data-testid="control-button-skip-forward"]');
      if (nextButton) nextButton.click();
    })();
  ''';

  static const String previousScript = '''
    (function() {
      const prevButton = document.querySelector('[data-testid="control-button-skip-back"]');
      if (prevButton) prevButton.click();
    })();
  ''';

  static const String toggleShuffleScript = '''
    (function() {
      // Find shuffle button by looking for button with shuffle-related aria-label
      const buttons = document.querySelectorAll('button[aria-label]');
      const shuffleButton = Array.from(buttons).find(button =>
        button.getAttribute('aria-label')?.toLowerCase().includes('shuffle')
      );
      if (shuffleButton) {
        shuffleButton.click();
      }
    })();
  ''';

  static const String toggleRepeatScript = '''
    (function() {
      const repeatButton = document.querySelector('[data-testid="control-button-repeat"]');
      if (repeatButton) repeatButton.click();
    })();
  ''';

  static const String toggleLikeScript = '''
    (function() {
      // Find the like button in the now-playing widget
      const likeButton = document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Lieblingssongs"]') ||
                        document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Playlist"]') ||
                        document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="favorite"]') ||
                        document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="like"]') ||
                        document.querySelector('[data-testid="now-playing-widget"] button[aria-checked]');
      if (likeButton) {
        likeButton.click();
      }
    })();
  ''';

  static String seekToPositionScript(double percentage) {
    return '''
      (function() {
        // Find the progress bar input element
        const progressInput = document.querySelector('[data-testid="playback-progressbar"] input[type="range"]');
        if (progressInput) {
          const max = parseInt(progressInput.max) || 0;
          const newValue = Math.floor(max * ${percentage});

          // Update the value
          progressInput.value = newValue;

          // Trigger input event to update the UI
          progressInput.dispatchEvent(new Event('input', { bubbles: true }));

          // Trigger change event to actually seek
          progressInput.dispatchEvent(new Event('change', { bubbles: true }));

          // Alternative: click on the progress bar itself
          const progressBar = document.querySelector('[data-testid="playback-progressbar"]');
          if (progressBar) {
            const rect = progressBar.getBoundingClientRect();
            const x = rect.left + (rect.width * ${percentage});
            const y = rect.top + (rect.height / 2);

            const clickEvent = new MouseEvent('click', {
              view: window,
              bubbles: true,
              cancelable: true,
              clientX: x,
              clientY: y
            });
            progressBar.dispatchEvent(clickEvent);
          }
        }
      })();
    ''';
  }

  static String searchScript(String query) {
    return '''
      (function() {
        const searchButton = document.querySelector('[href="/search"]');
        if (searchButton) {
          searchButton.click();
          setTimeout(() => {
            const searchInput = document.querySelector('input[data-testid="search-input"]');
            if (searchInput) {
              searchInput.value = '$query';
              searchInput.dispatchEvent(new Event('input', { bubbles: true }));
              searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
            }
          }, 500);
        }
      })();
    ''';
  }

  static const String searchResultsScript = '''
    const results = [];
    const songs = document.querySelectorAll('[data-testid="tracklist-row"]');
    songs.forEach((song, index) => {
      if (index < 10) {
        const title = song.querySelector('[data-testid="internal-track-link"] div')?.textContent || '';
        const artist = song.querySelector('[data-testid="internal-track-link"] + div a')?.textContent || '';
        results.push({ title, artist });
      }
    });
    JSON.stringify(results);
  ''';

  static const String logoutScript = '''
    (function() {
      const accountButton = document.querySelector('[data-testid="user-widget-link"]');
      if (accountButton) {
        accountButton.click();
        setTimeout(() => {
          const logoutButton = document.querySelector('[data-testid="user-widget-dropdown-logout"]');
          if (logoutButton) logoutButton.click();
        }, 500);
      }
    })();
  ''';

  static String playTrackAtIndexScript(int index) {
    return '''
      (function() {
        const songs = document.querySelectorAll('[data-testid="tracklist-row"]');
        if (songs[$index]) {
          const playButton = songs[$index].querySelector('[data-testid="more-button"]');
          if (playButton) {
            songs[$index].click();
            setTimeout(() => {
              const doubleClick = new MouseEvent('dblclick', {
                view: window,
                bubbles: true,
                cancelable: true
              });
              songs[$index].dispatchEvent(doubleClick);
            }, 100);
          }
        }
      })();
    ''';
  }

  static const String scrapeSongsScript = '''
    const songs = [];
    const songRows = document.querySelectorAll('[data-testid="tracklist-row"]');

    songRows.forEach((row, index) => {
      const titleElement = row.querySelector('[data-testid="internal-track-link"] div');
      const artistElement = row.querySelector('[data-testid="internal-track-link"]')?.parentElement?.nextElementSibling?.querySelector('a');
      const albumElement = row.querySelector('[data-testid="internal-track-link"]')?.parentElement?.nextElementSibling?.nextElementSibling?.querySelector('a');
      const imageElement = row.querySelector('img');
      const durationElement = row.querySelector('[data-testid="track-duration"]');

      if (titleElement) {
        songs.push({
          title: titleElement.textContent || '',
          artist: artistElement?.textContent || '',
          album: albumElement?.textContent || '',
          image: imageElement?.src || '',
          duration: durationElement?.textContent || '',
          index: index
        });
      }
    });

    JSON.stringify(songs);
  ''';

  static String scrollSpotifyPageScript(double offset) {
    return '''
      (function() {
        const mainView = document.querySelector('[data-testid="playlist-page"]') ||
                         document.querySelector('.main-view-container__scroll-node') ||
                         document.querySelector('[data-testid="track-list"]')?.parentElement;
        if (mainView) {
          mainView.scrollTop = $offset;
        }
      })();
    ''';
  }

  static const String loadMoreSongsScript = '''
    (function() {
      const mainView = document.querySelector('[data-testid="playlist-page"]') ||
                       document.querySelector('.main-view-container__scroll-node') ||
                       document.querySelector('[data-testid="track-list"]')?.parentElement;
      if (mainView) {
        mainView.scrollTop = mainView.scrollHeight;
      }
    })();
  ''';
}