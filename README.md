# Whelk

<img src="branding/preview.png" alt="" width="380">

Whelk is an SSH client for Android 8.1 and up. It also does Mosh, Telnet, serial over USB and Bluetooth, raw TCP and a local shell.

It started as a replacement for JuiceSSH, which was discontinued, so it has what JuiceSSH had with the Pro features: connections, identities and groups, snippets, port forwards, jump hosts, sync and a good terminal. Then it goes further, with an SFTP browser, serial consoles, agent forwarding, certificates and security keys.

It's at 0.5. It's new and there will be rough edges. Let me know what works and what doesn't **[on my discord](https://discord.gg/9Wun47jGC6)**, or open an issue here.

Dan (rm)

---

## What's in it

### Connections

- SSH, Mosh, Telnet, raw TCP, USB serial, Bluetooth serial and the phone's own shell
- Identities with a password, a key or both, shared between any number of connections
- Groups with colours, search, and the connections you used last at the top
- Type `user@host:port` in the search box to connect straight away
- Jump hosts, as many deep as you like, and HTTP or SOCKS proxies
- Keepalive, compression, environment variables, the server's character set, what Backspace sends, and a tmux session to attach to
- Host keys checked and saved, with a warning if one changes, and a screen to look over and forget them
- Reconnects by itself after a drop, keeping what was on screen
- Launcher shortcuts for recent connections, and any connection can go on the home screen

### Keys and logins

- Make Ed25519, ECDSA and RSA keys, or bring in OpenSSH, PEM, PKCS#8 and PuTTY keys, with or without a passphrase
- Keyboard-interactive logins, for two-factor codes
- OpenSSH certificates
- FIDO security keys (sk-ssh-ed25519 and sk-ecdsa) over USB or NFC, made on the phone or brought in
- Agent forwarding, asking before each use of a key

### The terminal

- Full colour, Unicode, mouse support, and a scrollback you can search
- Fira Mono, JetBrains Mono, Hack and Caskaydia Cove, all with powerline and icon symbols, or a font file of your own
- Colour schemes built in, or brought in from iTerm2, Xresources, Termux, Windows Terminal and Alacritty, set for the app or per connection
- A row of extra keys above the keyboard (Esc, Tab, Ctrl, Alt, arrows, function keys) that you can rearrange and add your own to
- Pinch or the volume keys to change the text size
- Swipe sideways to switch sessions, or tmux or screen windows
- Copy and paste, with pasted text cleaned of the invisible characters, odd spaces and curly quotes that web pages bring along
- Open the links on screen
- Several sessions at once, and typing into several at once
- Record a session to a text file, or every session to a folder
- Notifications when there's new output, when output stops, when words you pick show up, on the bell, or when a program asks for one

### Snippets and port forwards

- Snippets to type into a session, with `{{placeholders}}` that ask for a value first, and one to run on connect
- Local, remote and dynamic (SOCKS) port forwards, started by hand, with the connection, or from a home screen widget
- Open a local forward in the browser

### Files

- An SFTP browser: open, save to the phone, upload, rename, delete and make folders
- Edit a file in any editor app on the phone, and it goes back up each time you save

### Your setup

- Everything is kept encrypted on the phone, and the app can lock with your fingerprint or PIN
- Encrypted backups, and sync through a folder you pick (Syncthing, Nextcloud and so on), with no account or server of mine
- Share a group of connections with other people through its own file
- Bring in your `~/.ssh/config`, `known_hosts` or PuTTY sessions
- A light or dark app, or colours from your wallpaper

## Keyboard shortcuts

With a keyboard plugged in:

| | |
|---|---|
| Ctrl+Shift+C | copy what's selected |
| Ctrl+Shift+V | paste |
| Ctrl+Shift+F | find |
| Ctrl+Tab, Ctrl+Shift+Tab | next and previous session |
| Ctrl+Shift+plus, minus, 0 | bigger, smaller and normal text |

## Building

You need the Android SDK and NDK. The first build also builds mosh-client from source for each ABI, which takes a few minutes.

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :core:test              # the tests, against an SSH server inside the tests
tools/test-server/run.sh          # a throwaway OpenSSH server on 127.0.0.1:2222, user and password whelk
WHELK_OPENSSH=127.0.0.1:2222 ./gradlew :core:test   # the tests that need real OpenSSH too
```

From the emulator, the test server is at 10.0.2.2:2222.

| | |
|---|---|
| Minimum Android | 8.1 (API 27) |
| Built against | API 37 |
| UI | Kotlin, Jetpack Compose |
| SSH | sshj and BouncyCastle |

## Credits

- The terminal is the emulator and view from [Termux](https://github.com/termux/termux-app), under the Apache License 2.0, changed so it can run over SSH and the rest. See [terminal/NOTICE](terminal/NOTICE).
- [Mosh](https://mosh.org) is built from source, under the GPL version 3.
- USB serial uses [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android), under the MIT licence.
- Security keys use Yubico's [yubikit-android](https://github.com/Yubico/yubikit-android), under the Apache License 2.0.
- The fonts are [Nerd Fonts](https://www.nerdfonts.com) builds of Fira Mono, JetBrains Mono, Hack and Cascadia Code, under their own licences, which are in `app/src/main/assets/fonts`.

## Licence

Whelk is free software under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE).

Copyright © 2026 Dan Hunke.
