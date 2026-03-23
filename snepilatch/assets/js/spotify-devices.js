// Spotify Device Functions
// These functions manage device selection and detection in the Spotify web player

(function() {
    // Store device index mapping for switching
    window._deviceMapping = {};

    function findDeviceButton() {
        // Try multiple selectors to find the device/connect button
        // The button usually shows "Wiedergabe über DEVICE-NAME" or similar
        const selectors = [
            // Most common - the button with "Wiedergabe über" or device name
            'button[class*="UCkwzKM66KIIsICd6kew"]',
            'div[class*="UCkwzKM66KIIsICd6kew"] button',
            // Look for buttons with aria-label containing device/connect info
            'button[aria-label*="Wiedergabe"]',
            'button[aria-label*="Playing"]',
            'button[aria-label*="Connect"]',
            'button[aria-label*="connect"]',
            'button[aria-label*="Device"]',
            'button[aria-label*="device"]',
            '[data-testid="device-picker"]',
            'button[title*="Connect"]',
            // Look for the now playing bar device button
            '[data-testid="footer-now-playing-bar"] button',
            // Look for any button that contains device-related text
            'button:has(span[class*="text"])'
        ];

        for (const selector of selectors) {
            const buttons = document.querySelectorAll(selector);
            for (const button of buttons) {
                const text = button.textContent?.toLowerCase() || '';
                const ariaLabel = button.getAttribute('aria-label')?.toLowerCase() || '';

                // Check if button is related to playback device
                if (text.includes('wiedergabe') || text.includes('playing on') ||
                    text.includes('pc') || text.includes('gerät') ||
                    ariaLabel.includes('wiedergabe') || ariaLabel.includes('playing')) {
                    console.log('Found device button with text:', button.textContent?.trim());
                    return button;
                }
            }
        }

        return null;
    }

    function openDevicePanel() {
        return new Promise((resolve) => {
            const deviceButton = findDeviceButton();

            if (!deviceButton) {
                console.warn('Could not find device button');
                resolve(false);
                return;
            }

            // Check if panel is already open
            const isOpen = document.querySelector('[data-testid="PanelContainer_Id"]') ||
                          document.querySelector('[role="region"][aria-label*="Connect"]') ||
                          document.querySelector('[id*="Panel"]');

            if (isOpen) {
                console.log('Device panel already open');
                resolve(true);
                return;
            }

            // Click the button to open the device panel
            try {
                deviceButton.click();
                console.log('Clicked device button');

                // Wait for the panel to appear
                setTimeout(() => {
                    const panelOpened = document.querySelector('[data-testid="PanelContainer_Id"]') ||
                                      document.querySelector('[role="region"][aria-label*="Connect"]') ||
                                      document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]').length > 0;

                    resolve(panelOpened ? true : false);
                }, 300);
            } catch (e) {
                console.error('Error clicking device button:', e);
                resolve(false);
            }
        });
    }

    // Inject getDevices function into window
    window.getDevices = function() {
        try {
            const devices = [];
            let activeDeviceId = null;

            // Look for device list items in the Spotify UI
            // Spotify uses data-testid="device-picker-row-sidepanel" for device rows
            let deviceRows = document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]');

            // If no rows found, try to open the device panel
            if (deviceRows.length === 0) {
                console.log('No device rows visible, attempting to open device panel...');

                // Try to find and click the device button
                const deviceButton = findDeviceButton();
                if (deviceButton) {
                    deviceButton.click();
                    // Small delay for panel to render
                    const startTime = Date.now();
                    while (Date.now() - startTime < 500) {
                        deviceRows = document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]');
                        if (deviceRows.length > 0) break;
                    }
                }
            }

            if (deviceRows.length === 0) {
                console.warn('Device selector not found in UI');
                return JSON.stringify([]);
            }

            window._deviceMapping = {};

            deviceRows.forEach((row, index) => {
                try {
                    // Get device name from the row - try multiple selectors
                    let name = 'Unknown Device';

                    // First try: data-testid="list-row-title"
                    let titleElement = row.querySelector('[data-testid="list-row-title"]');
                    if (titleElement?.textContent?.trim()) {
                        name = titleElement.textContent.trim();
                    } else {
                        // Second try: get text from span inside the title element
                        const spans = row.querySelectorAll('span');
                        for (const span of spans) {
                            const text = span.textContent?.trim() || '';
                            if (text && text.length > 0 && !text.includes('Mit diesem Gerät')) {
                                name = text;
                                break;
                            }
                        }
                    }

                    console.log(`Device ${index}: "${name}"`);

                    // Check if this is the active device
                    // Look for the header section which contains the currently active device
                    const headerSection = document.querySelector('[data-bottom-row="start-jam"]');
                    const isCurrentDevice = headerSection?.textContent?.includes(name) || false;

                    // Also check if this row has any visual indicator of being active
                    const hasActiveIndicator = row.getAttribute('aria-selected') === 'true' ||
                                              row.textContent?.includes('Aktuelles Gerät');

                    const isActive = isCurrentDevice || hasActiveIndicator || false;

                    // Determine device type from icon or name
                    let deviceType = 'unknown';
                    const lowerName = name.toLowerCase();
                    if (lowerName.includes('browser') || lowerName.includes('web') || lowerName.includes('opera')) {
                        deviceType = 'browser';
                    } else if (lowerName.includes('phone') || lowerName.includes('mobile')) {
                        deviceType = 'phone';
                    } else if (lowerName.includes('speaker') || lowerName.includes('home')) {
                        deviceType = 'speaker';
                    } else if (lowerName.includes('computer') || lowerName.includes('desktop') || lowerName.includes('laptop') || lowerName.includes('pc') || lowerName.includes('niclas')) {
                        deviceType = 'computer';
                    } else if (lowerName.includes('watch')) {
                        deviceType = 'watch';
                    } else if (lowerName.includes('tablet') || lowerName.includes('ipad')) {
                        deviceType = 'tablet';
                    } else if (lowerName.includes('tv')) {
                        deviceType = 'tv';
                    }

                    const deviceId = 'device_' + index;

                    // Store the row reference for switching
                    window._deviceMapping[deviceId] = row;

                    const device = {
                        id: deviceId,
                        name: name,
                        type: deviceType,
                        isActive: isActive,
                        volumePercent: null
                    };

                    if (isActive) {
                        activeDeviceId = deviceId;
                    }

                    devices.push(device);
                } catch (e) {
                    console.warn('Error parsing device row:', e);
                }
            });

            return JSON.stringify(devices);
        } catch (e) {
            console.error('Error getting devices:', e);
            return JSON.stringify([]);
        }
    };

    // Inject switchDevice function into window
    window.switchDevice = function(deviceId) {
        try {
            console.log('Attempting to switch to device:', deviceId);

            // First, ensure the device panel is open
            const panelVisible = document.querySelector('[data-testid="PanelContainer_Id"]') ||
                                document.querySelector('[role="region"][aria-label*="Connect"]') ||
                                (document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]').length > 0);

            if (!panelVisible) {
                console.warn('Device panel not visible, attempting to open it first...');
                const deviceButton = findDeviceButton();
                if (deviceButton) {
                    deviceButton.click();
                    // Wait for panel to appear
                    const startTime = Date.now();
                    while (Date.now() - startTime < 500) {
                        if (document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]').length > 0) {
                            console.log('Device panel opened successfully');
                            break;
                        }
                    }
                }
            }

            // Get the device row from our mapping
            let deviceRow = window._deviceMapping[deviceId];

            if (!deviceRow || !document.body.contains(deviceRow)) {
                console.warn('Device row not found or removed from DOM for ID:', deviceId);
                // Try to find it by searching again
                const allRows = document.querySelectorAll('[data-testid="device-picker-row-sidepanel"]');
                const index = parseInt(deviceId.split('_')[1] || '0');
                if (allRows[index]) {
                    console.log('Found device row at index:', index);
                    clickDeviceRow(allRows[index]);
                    return true;
                }
                console.error('Could not find device row at index:', index);
                return false;
            }

            return clickDeviceRow(deviceRow);
        } catch (e) {
            console.error('Error switching device:', e);
            return false;
        }

        function clickDeviceRow(row) {
            try {
                console.log('Clicking device row...');

                // Try multiple approaches to find and click the clickable element

                // Approach 1: Find the direct button with role="button"
                let clickTarget = row.querySelector('[role="button"]');
                if (clickTarget) {
                    console.log('Found clickable element with [role="button"]');
                    clickTarget.click();
                    console.log('Device switched successfully');
                    return true;
                }

                // Approach 2: Find a button element
                clickTarget = row.querySelector('button');
                if (clickTarget) {
                    console.log('Found clickable element with button tag');
                    clickTarget.click();
                    console.log('Device switched successfully');
                    return true;
                }

                // Approach 3: Find any div with role="button" or role="menuitem"
                clickTarget = row.querySelector('[role="menuitem"]') || row.querySelector('[role="option"]');
                if (clickTarget) {
                    console.log('Found clickable element with menuitem/option role');
                    clickTarget.click();
                    console.log('Device switched successfully');
                    return true;
                }

                // Approach 4: Try clicking the row itself if it's interactive
                const rowRole = row.getAttribute('role');
                console.log('Row role:', rowRole);
                if (rowRole === 'button' || rowRole === 'menuitem' || rowRole === 'option') {
                    console.log('Row itself is clickable, clicking it directly');
                    row.click();
                    console.log('Device switched successfully');
                    return true;
                }

                // Approach 5: Find any element that looks clickable
                const allChildren = row.querySelectorAll('*');
                for (const child of allChildren) {
                    const clickable = child.getAttribute('role') === 'button' ||
                                    child.tagName === 'BUTTON' ||
                                    child.style.cursor === 'pointer' ||
                                    window.getComputedStyle(child).cursor === 'pointer';

                    if (clickable) {
                        console.log('Found clickable child:', child.tagName, child.getAttribute('role'));
                        child.click();
                        console.log('Device switched successfully');
                        return true;
                    }
                }

                // Last resort: Click the row container itself
                console.warn('No clickable element found, trying row click as last resort');
                row.click();
                console.log('Device switched successfully (last resort click)');
                return true;

            } catch (e) {
                console.error('Error clicking device:', e);
                return false;
            }
        }
    };

    // Inject refreshDevices function to manually refresh device list
    window.refreshDevices = function() {
        // Clear any cached device info and return fresh data
        return window.getDevices();
    };
})();
