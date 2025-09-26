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
          const newValue = Math.floor(max * $percentage);

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
            const x = rect.left + (rect.width * $percentage);
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
    (function() {
      const results = [];
      const songs = document.querySelectorAll('[data-testid="tracklist-row"]');
      songs.forEach((song, index) => {
        if (index < 10) {
          const title = song.querySelector('[data-testid="internal-track-link"] div')?.textContent || '';
          const artist = song.querySelector('[data-testid="internal-track-link"] + div a')?.textContent || '';
          results.push({ title, artist });
        }
      });
      return JSON.stringify(results);
    })();
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
    (function() {
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

      return JSON.stringify(songs);
    })();
  ''';

  static String scrollSpotifyPageScript(double offset) {
    return '''
      (function() {
        const mainView = document.querySelector('#main') ||
                         document.querySelector('.main-view-container__scroll-node') ||
                         document.querySelector('[data-testid="playlist-page"]');
        if (mainView) {
          mainView.scrollTop = $offset;
        }
      })();
    ''';
  }

  static const String initPlaylistControllerScript = '''
    // Spotify Playlist Controller
    (function() {
        class SpotifyPlaylistController {
            constructor() {
                this.tracks = new Map();
                this.isLoading = false;
                this.lastScrollPosition = 0;
                console.log('SpotifyPlaylistController initialized');

                // Do initial scan
                this._scanCurrentTracks();
            }

            // Scan and add tracks currently in DOM
            _scanCurrentTracks() {
                const trackRows = document.querySelectorAll('[data-testid="tracklist-row"]');
                let newCount = 0;

                trackRows.forEach((row) => {
                    try {
                        // Get track position
                        const positionEl = row.querySelector('[aria-colindex="1"] span');
                        const position = positionEl ? parseInt(positionEl.textContent.trim()) : null;

                        // Get track link for unique ID
                        const trackLink = row.querySelector('a[data-testid="internal-track-link"]');
                        const trackUrl = trackLink ? trackLink.href : null;
                        const title = trackLink ? trackLink.textContent.trim() : null;

                        if (!trackUrl || !title) return;

                        // Skip if we already have this track
                        if (this.tracks.has(trackUrl)) return;

                        // Get cover
                        const coverImg = row.querySelector('img[src*="i.scdn.co/image"]');
                        const coverUrl = coverImg ? coverImg.src : null;

                        // Get artists
                        const artistLinks = row.querySelectorAll('a[href*="/artist/"]');
                        const artists = Array.from(artistLinks).map(link => link.textContent.trim());

                        // Get album
                        const albumLink = row.querySelector('a[href*="/album/"]');
                        const album = albumLink ? albumLink.textContent.trim() : null;

                        // Get duration
                        const durationEl = row.querySelector('[data-testid="track-duration"]');
                        const duration = durationEl ? durationEl.textContent.trim() : null;

                        // Store track with position as key for ordering
                        this.tracks.set(trackUrl, {
                            position: position || this.tracks.size + 1,
                            title: title,
                            artists: artists,
                            album: album || 'Unknown Album',
                            duration: duration,
                            coverUrl: coverUrl,
                            trackUrl: trackUrl
                        });

                        newCount++;
                    } catch (err) {
                        console.error('Error parsing track:', err);
                    }
                });

                return newCount;
            }

            // Load more tracks by scrolling down
            async loadMore() {
                if (this.isLoading) {
                    console.log('Already loading...');
                    return { success: false, message: 'Already loading' };
                }

                this.isLoading = true;
                const initialCount = this.tracks.size;

                try {
                    // Find the last track row
                    const trackRows = document.querySelectorAll('[data-testid="tracklist-row"]');
                    if (trackRows.length === 0) {
                        return { success: false, message: 'No tracks found in DOM' };
                    }

                    const lastTrack = trackRows[trackRows.length - 1];

                    // Scroll to last track
                    lastTrack.scrollIntoView({ behavior: 'smooth', block: 'end' });

                    // Wait for new content
                    await this._waitForNewContent();

                    // Scan for new tracks
                    const newTracks = this._scanCurrentTracks();

                    const result = {
                        success: true,
                        previousCount: initialCount,
                        currentCount: this.tracks.size,
                        newTracks: this.tracks.size - initialCount,
                        totalLoaded: this.tracks.size
                    };

                    console.log('Loaded ' + result.newTracks + ' new tracks. Total: ' + result.totalLoaded);
                    return result;

                } finally {
                    this.isLoading = false;
                }
            }

            // Wait for new content to load
            _waitForNewContent(timeout = 2000) {
                return new Promise((resolve) => {
                    const startTime = Date.now();
                    const checkInterval = 100;

                    const check = () => {
                        const currentRows = document.querySelectorAll('[data-testid="tracklist-row"]').length;

                        if (Date.now() - startTime > timeout) {
                            resolve();
                            return;
                        }

                        // Check again
                        setTimeout(check, checkInterval);
                    };

                    // Start checking after initial delay for scroll animation
                    setTimeout(check, 500);
                });
            }

            // Get all tracks collected so far
            get() {
                const trackArray = Array.from(this.tracks.values())
                    .sort((a, b) => (a.position || 999) - (b.position || 999))
                    .map((track, index) => ({
                        index: index + 1,
                        position: track.position,
                        title: track.title,
                        artists: track.artists,
                        album: track.album,
                        duration: track.duration,
                        coverUrl: track.coverUrl
                    }));

                return {
                    tracks: trackArray,
                    count: trackArray.length
                };
            }

            // Clear all collected data and rescan
            reset() {
                this.tracks.clear();
                this._scanCurrentTracks();
                const count = this.tracks.size;
                console.log('Reset complete. Found ' + count + ' tracks in current view.');
                return { count };
            }

            // Get info about current state
            getInfo() {
                const visibleTracks = document.querySelectorAll('[data-testid="tracklist-row"]').length;
                return {
                    totalCollected: this.tracks.size,
                    visibleInDOM: visibleTracks,
                    isLoading: this.isLoading,
                    firstTrack: this.get().tracks[0],
                    lastTrack: this.get().tracks[this.tracks.size - 1]
                };
            }
        }

        // Create or reset controller
        if (!window.PlaylistController) {
            window.PlaylistController = new SpotifyPlaylistController();
            window.pc = window.PlaylistController; // Short alias
        } else {
            window.PlaylistController.reset();
        }

        return true;
    })();
  ''';

  static const String loadMoreSongsScript = '''
    (async function() {
        if (!window.PlaylistController) {
            console.error('PlaylistController not initialized');
            return JSON.stringify({ success: false, message: 'Controller not initialized' });
        }

        // Force reset if stuck for too long
        if (window.PlaylistController.isLoading) {
            if (!window.PlaylistController._loadingStartTime) {
                window.PlaylistController._loadingStartTime = Date.now();
            } else if (Date.now() - window.PlaylistController._loadingStartTime > 5000) {
                console.log('Force resetting stuck loading state after 5 seconds');
                window.PlaylistController.isLoading = false;
                window.PlaylistController._loadingStartTime = null;
            }
        }

        const result = await window.PlaylistController.loadMore();
        if (result.success) {
            window.PlaylistController._loadingStartTime = null;
        }
        return JSON.stringify(result);
    })();
  ''';

  static const String getLoadedTracksScript = '''
    (function() {
        if (!window.PlaylistController) {
            return JSON.stringify({ tracks: [], count: 0 });
        }

        return JSON.stringify(window.PlaylistController.get());
    })();
  ''';

  static const String debugScrollScript = '''
    (function() {
      const tracks = document.querySelectorAll('[data-testid="tracklist-row"]');
      const mainContainer = document.querySelector('#main');
      const info = {
        tracksInDOM: tracks.length,
        mainContainerExists: !!mainContainer,
        mainScrollTop: mainContainer ? mainContainer.scrollTop : 0,
        mainScrollHeight: mainContainer ? mainContainer.scrollHeight : 0,
        controllerLoading: window.PlaylistController ? window.PlaylistController.isLoading : 'no controller',
        tracksLoaded: window.PlaylistController ? window.PlaylistController.tracks.size : 0
      };
      console.log('Debug info:', JSON.stringify(info));
      return JSON.stringify(info);
    })();
  ''';

  static const String resetLoadingStateScript = '''
    (function() {
      if (window.PlaylistController) {
        window.PlaylistController.isLoading = false;
        window.PlaylistController._lastLoadingTime = null;
        console.log('Reset loading state');
        return true;
      }
      return false;
    })();
  ''';

  static const String openLikedSongsScript = '''
    (function() {
      // Find the Liked Songs element by various methods
      let likedSongsElement = null;

      // Method 1: Find by aria-labelledby
      likedSongsElement = document.querySelector('[aria-labelledby="listrow-title-spotify:collection:tracks"]');

      // Method 2: Find by the liked songs image
      if (!likedSongsElement) {
        const likedSongsImage = document.querySelector('img[src*="liked-songs-64.png"], img[src*="liked-songs-300.png"], img[src*="liked-songs-640.png"]');
        if (likedSongsImage) {
          likedSongsElement = likedSongsImage.closest('a, [role="gridcell"], div[data-testid]');
        }
      }

      // Method 3: Find by link href
      if (!likedSongsElement) {
        likedSongsElement = document.querySelector('a[href="/collection/tracks"]');
      }

      // Method 4: Find by text content
      if (!likedSongsElement) {
        const elements = document.querySelectorAll('a, div[role="gridcell"]');
        for (const el of elements) {
          if (el.textContent && (el.textContent.includes('Lieblingssongs') || el.textContent.includes('Liked Songs'))) {
            likedSongsElement = el;
            break;
          }
        }
      }

      if (likedSongsElement) {
        // Click the element to navigate to liked songs
        likedSongsElement.click();
        return true;
      }

      return false;
    })();
  ''';
}