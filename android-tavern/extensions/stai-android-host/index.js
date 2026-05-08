import { POPUP_RESULT, POPUP_TYPE, Popup } from '../../../popup.js';
import { SlashCommand } from '../../../slash-commands/SlashCommand.js';
import { SlashCommandParser } from '../../../slash-commands/SlashCommandParser.js';

const bridgeName = 'StaiAndroidHostBridge';
const menuButtonId = 'stai_android_host_menu_button';
const popupTitle = 'Android Host';

let slashCommandsRegistered = false;

function getBridge() {
    const bridge = globalThis[bridgeName];
    if (!bridge || typeof bridge.getHostVersionInfo !== 'function') {
        return null;
    }

    return bridge;
}

function getHostVersionInfo() {
    const bridge = getBridge();
    if (!bridge) {
        return null;
    }

    try {
        return JSON.parse(String(bridge.getHostVersionInfo() || '{}'));
    } catch (error) {
        console.error('Android Host: failed to parse host version info', error);
        return null;
    }
}

function showBridgeUnavailableToast() {
    toastr.warning('Android host bridge is unavailable in this page.', popupTitle);
}

function formatVersionSummary(info) {
    if (!info) {
        return 'Android host bridge unavailable';
    }

    const hostVersion = String(info.hostVersion || 'unknown');
    const apkVersionName = String(info.apkVersionName || 'unknown');
    const apkVersionCode = String(info.apkVersionCode || 'unknown');
    return `Host ${hostVersion} | APK ${apkVersionName} (${apkVersionCode})`;
}

async function openSettingsCommand() {
    const bridge = getBridge();
    if (!bridge || typeof bridge.openSettings !== 'function') {
        showBridgeUnavailableToast();
        return 'Android host bridge unavailable';
    }

    const opened = bridge.openSettings() === true;
    if (opened) {
        toastr.success('Opening Android host settings.', popupTitle);
        return 'Opening Android host settings';
    }

    toastr.warning('Android host settings are already opening.', popupTitle);
    return 'Android host settings are already opening';
}

async function showLogsBubbleCommand() {
    const bridge = getBridge();
    if (!bridge || typeof bridge.showFloatingLogsBubble !== 'function') {
        showBridgeUnavailableToast();
        return 'Android host bridge unavailable';
    }

    const shown = bridge.showFloatingLogsBubble() === true;
    if (shown) {
        toastr.success('Android log bubble is now visible.', popupTitle);
        return 'Android log bubble is now visible';
    }

    toastr.warning('Unable to show the Android log bubble right now.', popupTitle);
    return 'Unable to show the Android log bubble right now';
}

async function showVersionCommand() {
    const info = getHostVersionInfo();
    const summary = formatVersionSummary(info);
    if (info) {
        toastr.info(summary, popupTitle);
    } else {
        showBridgeUnavailableToast();
    }
    return summary;
}

function buildInfoRow(label, value) {
    const row = document.createElement('div');
    row.style.display = 'flex';
    row.style.flexDirection = 'column';
    row.style.gap = '4px';

    const title = document.createElement('strong');
    title.textContent = label;
    row.appendChild(title);

    const body = document.createElement('span');
    body.textContent = value;
    row.appendChild(body);

    return row;
}

function buildPopupContent(info) {
    const wrapper = document.createElement('div');
    wrapper.style.display = 'flex';
    wrapper.style.flexDirection = 'column';
    wrapper.style.gap = '12px';

    const description = document.createElement('p');
    description.style.margin = '0';
    description.textContent = info
        ? 'Use the Android host to open settings or reveal the floating logs bubble without restarting Tavern.'
        : 'Android host bridge is unavailable on this page.';
    wrapper.appendChild(description);

    if (!info) {
        return wrapper;
    }

    wrapper.appendChild(buildInfoRow('Host Version', String(info.hostVersion || 'unknown')));
    wrapper.appendChild(
        buildInfoRow(
            'APK Version',
            `${String(info.apkVersionName || 'unknown')} (${String(info.apkVersionCode || 'unknown')})`
        )
    );
    wrapper.appendChild(
        buildInfoRow(
            'Floating Logs Bubble',
            info.floatingLogBubbleEnabled ? 'Enabled' : 'Disabled'
        )
    );
    wrapper.appendChild(
        buildInfoRow(
            'Local Service',
            info.serverReady ? 'Running' : 'Starting or paused'
        )
    );

    return wrapper;
}

async function openAndroidHostPopup() {
    const info = getHostVersionInfo();
    const popup = new Popup(buildPopupContent(info), POPUP_TYPE.TEXT, '', {
        okButton: 'Close',
        cancelButton: false,
        customButtons: info ? [
            {
                text: 'Open Settings',
                result: POPUP_RESULT.CUSTOM1,
                icon: 'fa-sliders',
            },
            {
                text: 'Show Logs Bubble',
                result: POPUP_RESULT.CUSTOM2,
                icon: 'fa-align-left',
            },
        ] : null,
    });

    const result = await popup.show();
    if (result === POPUP_RESULT.CUSTOM1) {
        await openSettingsCommand();
    } else if (result === POPUP_RESULT.CUSTOM2) {
        await showLogsBubbleCommand();
    }
}

function addMenuButton() {
    if (document.getElementById(menuButtonId)) {
        return;
    }

    const menu = document.getElementById('extensionsMenu');
    if (!(menu instanceof HTMLElement)) {
        window.setTimeout(addMenuButton, 500);
        return;
    }

    const button = document.createElement('div');
    button.id = menuButtonId;
    button.classList.add('list-group-item', 'flex-container', 'flexGap5');

    const icon = document.createElement('div');
    icon.classList.add('fa-solid', 'fa-mobile-screen-button', 'extensionsMenuExtensionButton');
    button.appendChild(icon);

    const text = document.createElement('span');
    text.textContent = popupTitle;
    button.appendChild(text);

    button.addEventListener('click', () => {
        openAndroidHostPopup();
    });

    menu.appendChild(button);
}

function registerSlashCommands() {
    if (slashCommandsRegistered) {
        return;
    }

    slashCommandsRegistered = true;

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host',
        callback: async () => {
            await openAndroidHostPopup();
            return formatVersionSummary(getHostVersionInfo());
        },
        returns: 'Android host version summary',
        helpString: `
            <div>
                Opens the Android Host popup with host version details and quick actions.
            </div>
        `,
    }));

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host-settings',
        callback: openSettingsCommand,
        returns: 'status message',
        helpString: `
            <div>
                Opens the Android host settings screen without stopping Tavern first.
            </div>
        `,
    }));

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host-logs',
        callback: showLogsBubbleCommand,
        returns: 'status message',
        helpString: `
            <div>
                Enables and reveals the Android floating logs bubble.
            </div>
        `,
    }));

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host-version',
        callback: showVersionCommand,
        returns: 'Android host version summary',
        helpString: `
            <div>
                Shows the current Android host and APK versions.
            </div>
        `,
    }));
}

export async function activate() {
    registerSlashCommands();
    addMenuButton();
}