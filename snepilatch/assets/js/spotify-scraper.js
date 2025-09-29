// Spotify Scraper Functions
// These functions scrape data from the Spotify web player

(function() {
    // Quick login status check - returns true if logged in
    window.checkLoginStatus = function() {
        try {
            // Check if login button exists
            const loginButton = document.querySelector('[data-testid="login-button"]');

            // If login button exists, user is NOT logged in
            if (loginButton) {
                // Login button found - user is NOT logged in
                return false;
            }

            // Also check for user menu button as positive confirmation
            const userButton = document.querySelector('[data-testid="user-widget-link"], button[aria-label*="User" i], button[aria-label*="Profile" i]');

            // User is logged in if login button doesn't exist AND we have some indication of a user
            const isLoggedIn = !loginButton && (userButton !== null || document.cookie.includes('sp_'));
            // Login status check
            return isLoggedIn;
        } catch (e) {
            console.error('Error checking login status:', e);
            return false;
        }
    };
    // Inject getPlayingInfo into window
    window.getPlayingInfo = function() {
        try {
            const playButton = document.querySelector('[data-testid="control-button-playpause"]');
            const isPlaying = playButton.getAttribute('aria-label').includes('Pause');

            const trackElement = document.querySelector('[data-testid="context-item-info-title"]');
            const artistElement = document.querySelector('[data-testid="context-item-info-artist"]');
            const albumArtElement = document.querySelector('[data-testid="cover-art-image"]');

            // Check if current track is liked
            const likeButton = document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Lieblingssongs"], [data-testid="now-playing-widget"] button[aria-label*="Playlist"], [data-testid="now-playing-widget"] button[aria-label*="favorite"], [data-testid="now-playing-widget"] button[aria-label*="like"]');
            const isLiked = likeButton?.getAttribute('aria-checked') === 'true' || false;

            // Check shuffle state - detect off, normal, or enhanced
            const shuffleButton = document.querySelector('button[aria-label*="shuffle" i], button[aria-label*="Shuffle" i]');
            let shuffleMode = 'off';

            if (shuffleButton) {
                const ariaLabel = shuffleButton.getAttribute('aria-label')?.toLowerCase() || '';

                if (ariaLabel.includes('smart shuffle') && ariaLabel.includes('deaktivieren')) {
                    shuffleMode = 'enhanced';
                } else if (!ariaLabel.includes('smart') && ariaLabel.includes('deaktivieren')) {
                    shuffleMode = 'normal';
                } else if (ariaLabel.includes('smart shuffle') && ariaLabel.includes('aktivieren')) {
                    shuffleMode = 'normal';
                } else if (!ariaLabel.includes('smart') && ariaLabel.includes('aktivieren')) {
                    shuffleMode = 'off';
                }
            }

            // Check repeat state
            const repeatButton = document.querySelector('[data-testid="control-button-repeat"]');
            const repeatAriaChecked = repeatButton?.getAttribute('aria-checked');
            let repeatMode = 'off';
            if (repeatAriaChecked === 'true') {
                repeatMode = 'all';
            } else if (repeatAriaChecked === 'mixed') {
                repeatMode = 'one';
            }

            // Get progress bar data
            const positionElement = document.querySelector('[data-testid="playback-position"]');
            const durationElement = document.querySelector('[data-testid="playback-duration"]');
            const progressBarInput = document.querySelector('[data-testid="playback-progressbar"] input[type="range"]');

            let currentTime = positionElement?.textContent || '0:00';
            let duration = durationElement?.textContent || '0:00';
            let progressMs = 0;
            let durationMs = 0;

            if (progressBarInput) {
                progressMs = parseInt(progressBarInput.value) || 0;
                durationMs = parseInt(progressBarInput.max) || 0;
            }

            // Check for video in Now Playing View
            let videoUrl = null;
            let videoThumbnail = null;

            // Trigger video check if track changed
            const currentTrackTitle = trackElement?.textContent || '';
            if (window.lastTrackTitle && window.lastTrackTitle !== currentTrackTitle && currentTrackTitle) {
                console.log('🎵 Track changed in scraper, checking for video...');
                if (window.checkForVideoCanvas) {
                    window.checkForVideoCanvas();
                }
            }
            window.lastTrackTitle = currentTrackTitle;

            // Get latest video frame if available
            if (window.latestVideoData && window.latestVideoData.success) {
                videoThumbnail = window.latestVideoData.frame;
                // Don't log every time, just when we first get it
                if (!window.hasLoggedVideoCapture) {
                    console.log('🖼️ Video frame available for Flutter');
                    window.hasLoggedVideoCapture = true;
                }
            }

            return JSON.stringify({
                isPlaying: isPlaying,
                track: trackElement?.textContent || '',
                artist: artistElement?.textContent || '',
                albumArt: albumArtElement?.src || '',
                isLiked: isLiked,
                shuffleMode: shuffleMode,
                repeatMode: repeatMode,
                currentTime: currentTime,
                duration: duration,
                progressMs: progressMs,
                durationMs: durationMs,
                videoUrl: videoUrl,
                videoThumbnail: videoThumbnail
            });
        } catch (e) {
            return JSON.stringify({
                isPlaying: false,
                track: '',
                artist: '',
                albumArt: '',
                isLiked: false,
                shuffleMode: 'off',
                repeatMode: 'off'
            });
        }
    };

    // Inject getUserInfo into window
    window.getUserInfo = function() {
        try {
            // Check if user is logged in
            const loginButton = document.querySelector('[data-testid="login-button"]');
            const signupButton = document.querySelector('[data-testid="signup-button"]');

            if (loginButton || signupButton) {
                return JSON.stringify({
                    isLoggedIn: false,
                    username: '',
                    email: '',
                    profileImage: ''
                });
            }

            let username = '';
            let profileImage = '';

            // Get username and profile image from user widget button - primary method
            const userButton = document.querySelector('[data-testid="user-widget-link"]');
            if (userButton) {
                // Try aria-label first (most reliable)
                username = userButton.getAttribute('aria-label')?.trim() || '';

                // If not in aria-label, try text content
                if (!username) {
                    username = userButton.textContent?.trim() || '';
                }

                // Get profile image from img element inside the button
                const imgElement = userButton.querySelector('img');
                if (imgElement) {
                    profileImage = imgElement.getAttribute('src') || '';

                    // Also check for username in img alt text if not found yet
                    if (!username) {
                        username = imgElement.getAttribute('alt')?.trim() || '';
                    }
                }
            }

            // Fallback methods if primary doesn't work
            if (!username) {
                const profileLink = document.querySelector('[href*="/user/"]');
                if (profileLink) {
                    username = profileLink.textContent?.trim() || '';
                }
            }

            if (!username) {
                const userMenuButton = document.querySelector('[data-testid="user-menu-button"]');
                if (userMenuButton) {
                    username = userMenuButton.getAttribute('aria-label')?.replace('User menu for', '').trim() || '';
                }
            }

            // Try to get username from the page title or other elements
            if (!username) {
                const profileName = document.querySelector('h1')?.textContent;
                if (profileName && !profileName.includes('Spotify')) {
                    username = profileName;
                }
            }

            return JSON.stringify({
                isLoggedIn: true,
                username: username || 'Spotify User',
                email: '',
                profileImage: profileImage || ''
            });
        } catch (e) {
            return JSON.stringify({
                isLoggedIn: false,
                username: '',
                email: '',
                profileImage: ''
            });
        }
    };

    // Inject getSongs into window
    window.getSongs = function() {
        try {
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
        } catch (e) {
            return JSON.stringify([]);
        }
    };

    // Inject getSearchResults into window
    window.getSearchResults = function() {
        try {
            const results = [];

            // Find all search result track rows
            const trackRows = document.querySelectorAll('[data-testid="search-tracks-result"] [data-testid="tracklist-row"], [data-testid="track-list"] [data-testid="tracklist-row"]');

            trackRows.forEach((row, index) => {
                if (index < 20) { // Limit to first 20 results
                    try {
                        // Get title and track link
                        const titleLink = row.querySelector('a[href*="/track/"]');
                        const title = titleLink ? titleLink.textContent.trim() : '';

                        // Get artist
                        const artistLink = row.querySelector('a[href*="/artist/"]');
                        const artist = artistLink ? artistLink.textContent.trim() : 'Unknown Artist';

                        // Get album art - same as PlaylistController
                        const albumArt = row.querySelector('img[src*="i.scdn.co/image"]');
                        const imageUrl = albumArt ? albumArt.src : null;

                        // Get duration
                        const durationEl = row.querySelector('.Q4mk1oUBywa1PuZZZ0Mr') ||
                                         row.querySelector('[data-encore-id="text"]:last-child');
                        const duration = durationEl ? durationEl.textContent.trim() : '';

                        if (title) {
                            results.push({
                                index: index + 1, // 1-based index for display
                                title: title,
                                artist: artist,
                                imageUrl: imageUrl,
                                duration: duration
                            });
                        }
                    } catch (err) {
                        console.error('Error parsing search result:', err);
                    }
                }
            });

            console.log('Found ' + results.length + ' search results');
            return JSON.stringify(results);
        } catch (e) {
            console.error('Error getting search results:', e);
            return JSON.stringify([]);
        }
    };

    // Function to play a search result
    window.playSearchResult = function(index) {
        const trackRows = document.querySelectorAll('[data-testid="search-tracks-result"] [data-testid="tracklist-row"], [data-testid="track-list"] [data-testid="tracklist-row"]');

        if (trackRows[index - 1]) { // Convert to 0-based index
            const playButton = trackRows[index - 1].querySelector('button[aria-label*="abspielen"], button[aria-label*="play"], button[aria-label*="Play"]');
            if (playButton) {
                playButton.click();
                return true;
            }

            // Fallback: double-click the row
            trackRows[index - 1].dispatchEvent(new MouseEvent('dblclick', {
                view: window,
                bubbles: true,
                cancelable: true
            }));
            return true;
        }
        return false;
    };

    console.log('Spotify scraper functions injected into window');
    return true;
})();