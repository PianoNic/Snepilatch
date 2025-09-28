// Spotify Action Functions
// These functions control playback and navigation in the Spotify web player

(function() {
    // Inject play function into window
    window.spotifyPlay = function() {
        const playButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (playButton && playButton.getAttribute('aria-label')?.includes('Play')) {
            playButton.click();
            return true;
        }
        return false;
    };

    // Inject pause function into window
    window.spotifyPause = function() {
        const pauseButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (pauseButton && pauseButton.getAttribute('aria-label')?.includes('Pause')) {
            pauseButton.click();
            return true;
        }
        return false;
    };

    // Inject next function into window
    window.spotifyNext = function() {
        const nextButton = document.querySelector('[data-testid="control-button-skip-forward"]');
        if (nextButton) {
            nextButton.click();
            return true;
        }
        return false;
    };

    // Inject previous function into window
    window.spotifyPrevious = function() {
        const prevButton = document.querySelector('[data-testid="control-button-skip-back"]');
        if (prevButton) {
            prevButton.click();
            return true;
        }
        return false;
    };

    // Inject toggle shuffle function into window
    window.spotifyToggleShuffle = function() {
        const buttons = document.querySelectorAll('button[aria-label]');
        const shuffleButton = Array.from(buttons).find(button =>
            button.getAttribute('aria-label')?.toLowerCase().includes('shuffle')
        );
        if (shuffleButton) {
            shuffleButton.click();
            return true;
        }
        return false;
    };

    // Inject toggle repeat function into window
    window.spotifyToggleRepeat = function() {
        const repeatButton = document.querySelector('[data-testid="control-button-repeat"]');
        if (repeatButton) {
            repeatButton.click();
            return true;
        }
        return false;
    };

    // Inject toggle like function into window
    window.spotifyToggleLike = function() {
        const likeButton = document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Lieblingssongs"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Playlist"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="favorite"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="like"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-checked]');
        if (likeButton) {
            likeButton.click();
            return true;
        }
        return false;
    };

    // Inject seek function into window
    window.spotifySeek = function(percentage) {
        const progressInput = document.querySelector('[data-testid="playback-progressbar"] input[type="range"]');
        if (progressInput) {
            const max = parseInt(progressInput.max) || 0;
            const newValue = Math.floor(max * percentage);

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
                const x = rect.left + (rect.width * percentage);
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
            return true;
        }
        return false;
    };

    // Inject search function into window
    window.spotifySearch = function(query) {
        const searchButton = document.querySelector('[href="/search"]');
        if (searchButton) {
            searchButton.click();
            setTimeout(() => {
                const searchInput = document.querySelector('input[data-testid="search-input"]');
                if (searchInput) {
                    searchInput.value = query;
                    searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                    searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
                }
            }, 500);
            return true;
        }
        return false;
    };

    // Inject logout function into window
    window.spotifyLogout = function() {
        const accountButton = document.querySelector('[data-testid="user-widget-link"]');
        if (accountButton) {
            accountButton.click();
            setTimeout(() => {
                const logoutButton = document.querySelector('[data-testid="user-widget-dropdown-logout"]');
                if (logoutButton) logoutButton.click();
            }, 500);
            return true;
        }
        return false;
    };

    // Inject play track at index function into window
    window.spotifyPlayTrackAtIndex = function(trackNumber) {
        // Find track by its actual position number (not array index)
        const trackRows = document.querySelectorAll('[data-testid="tracklist-row"]');
        let targetRow = null;

        for (const row of trackRows) {
            const positionEl = row.querySelector('[aria-colindex="1"] span');
            if (positionEl) {
                const position = parseInt(positionEl.textContent.trim());
                if (position === trackNumber) {
                    targetRow = row;
                    break;
                }
            }
        }

        if (targetRow) {
            // Try to find and click the play button first
            const playButton = targetRow.querySelector('button[aria-label*="abspielen"], button[aria-label*="play"], button[aria-label*="Play"]');
            if (playButton) {
                playButton.click();
                console.log('Clicked play button for track #' + trackNumber);
                return true;
            }

            // Fallback: double-click the row to play
            const doubleClick = new MouseEvent('dblclick', {
                view: window,
                bubbles: true,
                cancelable: true
            });
            targetRow.dispatchEvent(doubleClick);
            console.log('Double-clicked track #' + trackNumber);
            return true;
        }

        console.log('Track #' + trackNumber + ' not found in DOM');
        return false;
    };

    // Inject scroll function into window
    window.spotifyScrollPage = function(offset) {
        const mainView = document.querySelector('#main') ||
                         document.querySelector('.main-view-container__scroll-node') ||
                         document.querySelector('[data-testid="playlist-page"]');
        if (mainView) {
            mainView.scrollTop = offset;
            return true;
        }
        return false;
    };

    // Inject open liked songs function into window
    window.spotifyOpenLikedSongs = function() {
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
            likedSongsElement.click();
            return true;
        }

        return false;
    };

    console.log('Spotify action functions injected into window');
    return true;
})();