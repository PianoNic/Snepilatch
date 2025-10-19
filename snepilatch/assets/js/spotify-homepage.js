// Spotify Homepage Functions
// These functions scrape and interact with the Spotify homepage

(function() {
    // Inject getHomepageShortcuts into window (the grid at the top)
    window.getHomepageShortcuts = function() {
        try {
            const shortcuts = [];

            // Find the shortcuts section (typically labeled "Guten Tag" or "Good afternoon")
            const shortcutsSection = document.querySelector('section[aria-label*="Guten"], section[aria-label*="Good"], section[aria-label*="afternoon"], section[aria-label*="evening"], section[aria-label*="morning"]');

            if (!shortcutsSection) {
                console.log('No shortcuts section found');
                return JSON.stringify([]);
            }

            // Find all shortcut cards (they have a specific structure)
            const shortcutCards = shortcutsSection.querySelectorAll('[draggable="true"]');

            shortcutCards.forEach(card => {
                // Get title from link or paragraph
                const titleEl = card.querySelector('p[class*="RidYQJ8faceMVnFLBEMC"], a p');
                const title = titleEl?.textContent?.trim() || '';

                // Get image
                const imgEl = card.querySelector('img[data-testid="shortcut-image"]');
                let imageUrl = imgEl?.src || '';

                // Upgrade image quality
                if (imageUrl && imageUrl.includes('ab67616d00004851')) {
                    imageUrl = imageUrl.replace('ab67616d00004851', 'ab67616d00001e02');
                }

                // Get href
                const linkEl = card.querySelector('a[href]');
                const href = linkEl?.getAttribute('href') || '';

                // Determine type from href
                let type = 'unknown';
                if (href.includes('/playlist/')) type = 'playlist';
                else if (href.includes('/album/')) type = 'album';
                else if (href.includes('/artist/')) type = 'artist';
                else if (href.includes('/collection')) type = 'collection';
                else if (href.includes('/user/')) type = 'collection';
                else if (href.includes('/episode/')) type = 'episode';

                // Extract ID from href
                let id = '';
                if (type === 'playlist' || type === 'album' || type === 'artist' || type === 'episode') {
                    const parts = href.split('/');
                    id = parts[parts.length - 1] || '';
                } else {
                    id = href;
                }

                // Check if currently playing (look for pause button or playing indicator)
                const playButton = card.querySelector('[data-testid="play-button"]');
                const isPlaying = playButton?.getAttribute('aria-label')?.toLowerCase().includes('pause') || false;

                // Check for progress bar (for episodes/podcasts)
                const progressBar = card.querySelector('[role="progressbar"]');
                let progressPercentage = null;
                if (progressBar) {
                    const ariaValueNow = progressBar.getAttribute('aria-valuenow');
                    if (ariaValueNow) {
                        progressPercentage = parseInt(ariaValueNow, 10);
                    }
                }

                if (title && imageUrl) {
                    shortcuts.push({
                        id: id,
                        title: title,
                        imageUrl: imageUrl,
                        href: href,
                        type: type,
                        isPlaying: isPlaying,
                        progressPercentage: progressPercentage
                    });
                }
            });

            return JSON.stringify(shortcuts);
        } catch (e) {
            console.error('Error getting homepage shortcuts:', e);
            return JSON.stringify([]);
        }
    };


    // Inject getHomepageSections into window
    window.getHomepageSections = function() {
        try {
            const sections = [];
            const seenTitles = new Set(); // Track section titles to avoid duplicates

            // Find all homepage sections with aria-labels
            // We only need sections, not other elements with these aria-labels
            const sectionElements = document.querySelectorAll('section[aria-label]');

            sectionElements.forEach(section => {
                const sectionTitle = section.getAttribute('aria-label') || '';

                // Skip if we've already processed this section title
                if (seenTitles.has(sectionTitle)) {
                    return;
                }

                const items = [];

                // Find all cards within the section
                const cards = section.querySelectorAll('[data-encore-id="card"], .RKstfK7T5nPbsDOYT6sT, [role="listitem"]');

                cards.forEach(card => {
                    // Get the title
                    const titleEl = card.querySelector('[data-encore-id="cardTitle"], .CardTitle__CardText-sc-1h38un4-1, p[title]');
                    const title = titleEl?.textContent?.trim() || titleEl?.getAttribute('title') || '';

                    // Get subtitle (artist, playlist creator, etc.)
                    const subtitleEl = card.querySelector('[data-encore-id="cardSubtitle"], .CardDetails__CardDetailText-sc-1gdonml-1');
                    const subtitle = subtitleEl?.textContent?.trim() || null;

                    // Get image URL
                    const imgEl = card.querySelector('img[data-testid="card-image"], img[data-testid="shortcut-image"]');
                    let imageUrl = imgEl?.src || '';

                    // Upgrade image quality if needed
                    if (imageUrl && imageUrl.includes('ab67616d00004851')) {
                        imageUrl = imageUrl.replace('ab67616d00004851', 'ab67616d00001e02');
                    }

                    // Get href for navigation
                    const linkEl = card.querySelector('a[href]');
                    const href = linkEl?.getAttribute('href') || '';

                    // Determine type from href
                    let type = 'unknown';
                    if (href.includes('/playlist/')) type = 'playlist';
                    else if (href.includes('/album/')) type = 'album';
                    else if (href.includes('/artist/')) type = 'artist';
                    else if (href.includes('/collection')) type = 'collection';
                    else if (href.includes('/user/')) type = 'collection';

                    // Extract ID from href
                    let id = '';
                    if (type === 'playlist' || type === 'album' || type === 'artist') {
                        const parts = href.split('/');
                        id = parts[parts.length - 1] || '';
                    } else {
                        id = href;
                    }

                    // Check if currently playing (for pause button)
                    const playButton = card.querySelector('[data-testid="play-button"]');
                    const isPlaying = playButton?.getAttribute('aria-label')?.includes('pausieren') ||
                                     playButton?.getAttribute('aria-label')?.includes('pause') ||
                                     false;

                    if (title && imageUrl) {
                        items.push({
                            id: id,
                            title: title,
                            subtitle: subtitle,
                            imageUrl: imageUrl,
                            href: href,
                            type: type,
                            isPlaying: isPlaying
                        });
                    }
                });

                if (items.length > 0) {
                    seenTitles.add(sectionTitle); // Mark this title as seen
                    sections.push({
                        title: sectionTitle,
                        sectionId: section.id || null,
                        items: items
                    });
                }
            });

            return JSON.stringify(sections);
        } catch (e) {
            console.error('Error getting homepage sections:', e);
            return JSON.stringify([]);
        }
    };

    // Function to play/pause a homepage item
    window.playHomepageItem = function(itemId, itemType) {
        try {
            console.log('Attempting to play item:', itemId, 'type:', itemType);

            // Method 1: Find by href in link elements
            const allLinks = document.querySelectorAll('a[href*="' + itemId + '"]');
            console.log('Found', allLinks.length, 'links with itemId');

            for (const link of allLinks) {
                // Look for play button in the same card/container
                const card = link.closest('[data-encore-id="card"], [role="listitem"], .RKstfK7T5nPbsDOYT6sT, article');
                if (card) {
                    const playBtn = card.querySelector('[data-testid="play-button"], button[aria-label*="Play"], button[aria-label*="play"], button[aria-label*="Wiedergabe"], button[aria-label*="wiedergabe"]');
                    if (playBtn) {
                        console.log('Found play button in card, clicking...');
                        playBtn.click();
                        return true;
                    }
                }
            }

            // Method 2: Find by looking at all cards
            const cards = document.querySelectorAll('[data-encore-id="card"], [role="listitem"], .RKstfK7T5nPbsDOYT6sT, article');
            console.log('Checking', cards.length, 'cards');

            for (const card of cards) {
                const linkEl = card.querySelector('a[href]');
                const href = linkEl?.getAttribute('href') || '';

                if (href.includes(itemId) || (itemType === 'collection' && href.includes('/collection'))) {
                    // Try multiple play button selectors
                    const playBtn = card.querySelector('[data-testid="play-button"], button[aria-label*="Play"], button[aria-label*="play"], button[aria-label*="Wiedergabe"], button[aria-label*="wiedergabe"]');
                    if (playBtn) {
                        console.log('Found play button via card search, clicking...');
                        playBtn.click();
                        return true;
                    }

                    // Try hovering first to reveal play button
                    const event = new MouseEvent('mouseenter', { bubbles: true });
                    card.dispatchEvent(event);

                    setTimeout(() => {
                        const playBtnAfterHover = card.querySelector('[data-testid="play-button"], button[aria-label*="Play"], button[aria-label*="play"], button[aria-label*="Wiedergabe"], button[aria-label*="wiedergabe"]');
                        if (playBtnAfterHover) {
                            console.log('Found play button after hover, clicking...');
                            playBtnAfterHover.click();
                        }
                    }, 100);

                    return true; // Return true even if we need to wait for hover
                }
            }

            // Method 3: If it's a collection (Liked Songs), try the specific selector
            if (itemType === 'collection' || itemId.includes('/collection')) {
                const likedSongsCard = document.querySelector('a[href="/collection/tracks"]')?.closest('[data-encore-id="card"], article, [role="listitem"]');
                if (likedSongsCard) {
                    const playBtn = likedSongsCard.querySelector('button');
                    if (playBtn) {
                        console.log('Found collection play button, clicking...');
                        playBtn.click();
                        return true;
                    }
                }
            }

            console.log('Could not find play button for item:', itemId);
            return false;
        } catch (e) {
            console.error('Error playing homepage item:', e);
            return false;
        }
    };

    // Function to navigate to a homepage item
    window.navigateToHomepageItem = function(href) {
        try {
            const currentUrl = window.location.href;
            const targetUrl = 'https://open.spotify.com' + href;

            // Check if we're already on this page
            if (currentUrl === targetUrl) {
                console.log('Already on target page');
                return true;
            }

            console.log('Attempting to navigate to: ' + targetUrl + ' from: ' + currentUrl);

            // Method 1: Try to find and click the actual link element by href
            const linkEl = document.querySelector(`a[href="${href}"]`);
            if (linkEl) {
                console.log('Found link element, clicking it...');
                // Make sure the link is visible before clicking
                linkEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
                // Use a small delay to ensure DOM is ready
                setTimeout(() => {
                    linkEl.click();
                }, 100);
                return true;
            }

            console.log('Link not found by href, trying SPA navigation...');

            // Method 2: Use Spotify's SPA navigation as fallback
            try {
                const history = window.history;
                history.pushState({}, '', targetUrl);
                const popstateEvent = new PopStateEvent('popstate', { state: {} });
                window.dispatchEvent(popstateEvent);
                console.log('Triggered SPA navigation to: ' + targetUrl);
                return true;
            } catch (spaErr) {
                console.error('SPA navigation also failed:', spaErr);
                return false;
            }
        } catch (err) {
            console.error('Error navigating to homepage item:', err);
            return false;
        }
    };

    // Function to click "Mehr anzeigen" button to expand results
    window.clickShowMoreButton = function() {
        try {
            console.log('Attempting to click "Mehr anzeigen" button...');

            // Try to find the button by class name
            const button = document.querySelector('button.NIccMWgYS0OibP_70DRO');

            if (button) {
                console.log('Found "Mehr anzeigen" button, clicking it...');
                button.click();
                return true;
            } else {
                console.log('Could not find "Mehr anzeigen" button');
                return false;
            }
        } catch (err) {
            console.error('Error clicking "Mehr anzeigen" button:', err);
            return false;
        }
    };

    // Function to get monthly listeners from artist page
    window.getArtistInfo = function() {
        try {
            console.log('Getting artist info...');

            const info = {};

            // Try to find monthly listeners text
            const textElements = document.querySelectorAll('[data-encore-id="text"]');
            for (const el of textElements) {
                const text = el.textContent?.trim() || '';
                // Look for patterns like "1,234,567 Hörer pro Monat" or "X monthly listeners"
                if (text.includes('Hörer pro Monat') || text.includes('monthly listener')) {
                    info.monthlyListeners = text;
                    console.log('Found monthly listeners:', text);
                    break;
                }
            }

            // Try alternative: look for numbers followed by listener text
            const allText = document.body.innerText;
            const listenerMatch = allText.match(/(\d{1,3}(?:[.,]\d{3})*)\s*(?:Hörer pro Monat|monthly listener)/);
            if (listenerMatch && !info.monthlyListeners) {
                info.monthlyListeners = listenerMatch[0];
                console.log('Found monthly listeners (alt):', listenerMatch[0]);
            }

            return JSON.stringify(info);
        } catch (err) {
            console.error('Error getting artist info:', err);
            return JSON.stringify({});
        }
    };

    // Function to follow/unfollow an artist
    window.toggleFollowArtist = function() {
        try {
            console.log('Attempting to toggle follow status...');

            // Find the follow button within the action bar
            const actionBar = document.querySelector('[data-testid="action-bar"]');

            if (actionBar) {
                // Find the secondary button (follow button) within the action bar
                const followButton = actionBar.querySelector('button[data-encore-id="buttonSecondary"]');

                if (followButton) {
                    console.log('Found follow button');
                    console.log('Clicking follow button...');
                    followButton.click();

                    // Wait for Spotify to update the UI and return the new status
                    return new Promise(resolve => {
                        setTimeout(() => {
                            // Check the new status after clicking
                            const newAriaLabel = followButton.getAttribute('aria-label') || '';
                            const newButtonText = followButton.textContent?.trim() || '';

                            console.log('After click - aria-label:', newAriaLabel);
                            console.log('After click - button text:', newButtonText);

                            const isNowFollowed = newAriaLabel.includes('Entfernen') ||
                                               newAriaLabel.includes('Remove from') ||
                                               newButtonText.length > 0 && !newButtonText.includes('Follow');

                            console.log('New follow status: ' + (isNowFollowed ? 'followed' : 'not followed'));
                            resolve(isNowFollowed);
                        }, 500); // Wait 500ms for UI to update
                    });
                }
            }

            console.log('Could not find follow button');
            return false;
        } catch (err) {
            console.error('Error toggling follow:', err);
            return false;
        }
    };

    // Function to check if artist is followed
    window.isArtistFollowed = function() {
        try {
            console.log('Checking if artist is followed...');

            // Find the follow button within the action bar
            const actionBar = document.querySelector('[data-testid="action-bar"]');

            if (actionBar) {
                const followButton = actionBar.querySelector('button[data-encore-id="buttonSecondary"]');

                if (followButton) {
                    // Check the aria-label to determine follow status
                    const ariaLabel = followButton.getAttribute('aria-label') || '';
                    const buttonText = followButton.textContent?.trim() || '';

                    console.log('Follow button aria-label:', ariaLabel);
                    console.log('Follow button text:', buttonText);

                    // If aria-label contains "Entfernen" (remove/unfollow) or "Remove from", artist is followed
                    // If text is empty or says "Follow", artist is not followed
                    const isFollowed = ariaLabel.includes('Entfernen') ||
                                     ariaLabel.includes('Remove from') ||
                                     buttonText.length > 0 && !buttonText.includes('Follow');

                    console.log('Artist is ' + (isFollowed ? 'followed' : 'not followed'));
                    return isFollowed;
                }
            }

            console.log('Could not find follow button');
            return false;
        } catch (err) {
            console.error('Error checking follow status:', err);
            return false;
        }
    };

    // Function to check if artist is currently playing
    window.isArtistPlaying = function() {
        try {
            console.log('Checking if artist is playing...');

            // Find the play button within the action bar
            const actionBar = document.querySelector('[data-testid="action-bar"]');

            if (actionBar) {
                const playButton = actionBar.querySelector('button[data-testid="play-button"]');

                if (playButton) {
                    const ariaLabel = playButton.getAttribute('aria-label') || '';
                    console.log('Play button aria-label:', ariaLabel);

                    // If aria-label is "Pause", the artist is currently playing
                    // If aria-label is "Play", the artist is not playing
                    const isPlaying = ariaLabel.toLowerCase().includes('pause');

                    console.log('Artist is ' + (isPlaying ? 'playing' : 'not playing'));
                    return isPlaying;
                }
            }

            console.log('Could not find play button');
            return false;
        } catch (err) {
            console.error('Error checking play status:', err);
            return false;
        }
    };

    // Function to toggle play/pause for artist
    window.toggleArtistPlayPause = function() {
        try {
            console.log('Attempting to toggle artist play/pause...');

            // Find the play button within the action bar
            const actionBar = document.querySelector('[data-testid="action-bar"]');

            if (actionBar) {
                const playButton = actionBar.querySelector('button[data-testid="play-button"]');

                if (playButton) {
                    console.log('Found play button, clicking it...');
                    playButton.click();

                    // Wait for UI update and return new status
                    return new Promise(resolve => {
                        setTimeout(() => {
                            const newAriaLabel = playButton.getAttribute('aria-label') || '';
                            const isNowPlaying = newAriaLabel.toLowerCase().includes('pause');
                            console.log('New play status: ' + (isNowPlaying ? 'playing' : 'not playing'));
                            resolve(isNowPlaying);
                        }, 300);
                    });
                }
            }

            console.log('Could not find play button');
            return false;
        } catch (err) {
            console.error('Error toggling play/pause:', err);
            return false;
        }
    };

    console.log('Spotify homepage functions injected into window');
    return true;
})();