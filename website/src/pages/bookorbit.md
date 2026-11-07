---
layout: ../layouts/Prose.astro
title: BookOrbit
description: Connect Voice to your BookOrbit server to stream, download and sync your audiobooks.
---

# Voice with BookOrbit

Connect Voice to your own BookOrbit server to browse its audiobooks in your library, stream them or download them for
offline listening. Position, completion and bookmarks sync with BookOrbit, and your listening appears in its statistics.
Voice talks directly to your server.

## Connect

BookOrbit is initially available behind the `bookorbit` feature flag. In Settings, tap the version area at the bottom
13 times to unlock Developer Menu, then turn on `bookorbit`. This enables the connection option; it does not sign in.

Open **Settings > Library > BookOrbit**, next to Audiobookshelf. Enter your server address, sign in with your BookOrbit
username and password, and select libraries. You can also choose BookOrbit when first adding a library. Only books
with supported audiobook files appear; ebooks and podcasts are not imported.

Voice supports one connected audiobook server at a time: BookOrbit or Audiobookshelf. Local audiobook folders remain
available with either. To change server providers, sign out of the current server first, then connect the other one.
Sign-out removes that provider's local downloads. Let a sync finish before switching when possible. BookOrbit keeps
queued changes under the original server/account identity for a later reconnect; it never sends them to another account.

Use your usual web address, including a reverse-proxy subdirectory if needed. Home-network HTTP addresses and VPNs
work. For a private certificate authority, install that authority in Android's certificate settings. Voice does not
offer a switch to ignore certificate errors. SSO-only servers need a password-enabled account, just as with the
Audiobookshelf integration.

### Cloudflare Access

If your BookOrbit hostname is protected by Cloudflare Access, create a service token and allow it in a **Service Auth**
policy for that application. On Voice's server address screen, select **Cloudflare Access** and paste the service
token's **Client ID** and **Client secret**. Use the final HTTPS address, including any BookOrbit subdirectory.
Then sign in with your usual BookOrbit username and password.

For an existing connection, open **Settings > Library > BookOrbit > Cloudflare Access**, or choose **Sign in** if
Voice asks you to sign in again. Replace the credentials and sign in to update them without removing your downloads.
To remove them, deselect **Cloudflare Access** before continuing. The server must be reachable with the updated
configuration.

[Set up a Cloudflare Access service token](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/).

## Listen and download

- Tap a book to stream it. Chapters, bookmarks, speed, sleep timer, silence skipping, volume boost, widgets and Android
  Auto use Voice's normal player.
- Long press a book to download it. The notification shows progress and can stop or retry a download.
- Downloads wait for unmetered Wi-Fi by default. BookOrbit settings has a mobile-data switch, downloaded books and
  used space.
- Downloaded books remain playable offline after restarting the app. Books without downloads show an unavailable
  badge when the server cannot be reached. Normal streaming does not fill the permanent download cache.
- Removing a download frees local space; it does not remove the book from your server. Names, covers and metadata
  still come from BookOrbit, with Voice's existing local overrides kept separately.

Do not also scan downloaded copies of the same server books as local files: those copies would appear separately.

## Progress, bookmarks and listening history

Voice syncs when it starts, opens the library or regains a connection, and sends position while listening and on pause.
Offline changes stay queued until the server can accept them. BookOrbit revision checks prevent an old update from
blindly overwriting another device's progress. When both sides changed while Voice is idle, the later captured action
wins; an intentional rewind is a valid action. An actively playing book is not jumped by a background pull.

Completion and resetting to the start sync with BookOrbit's read/unread status. Bookmarks can be created, renamed and
deleted on either side. A server deletion takes priority over an unsent rename. BookOrbit-only bookmark notes are
preserved when Voice renames a bookmark. BookOrbit keeps one server bookmark per position, so marks at the same position
are merged when syncing. Listening statistics
count actual active time, separately from position, and retrying an offline session does not add the same time twice.

BookOrbit can share status with an ebook and synchronize a paired read-aloud ebook. That is existing BookOrbit server
behavior; Voice sends audiobook updates and does not edit ebooks directly.

Edits to a BookOrbit book’s metadata stay in Voice.

## Server compatibility

This integration targets the native BookOrbit protocol inspected at
[`63e16ad`](https://github.com/bookorbit/bookorbit/tree/63e16ad757e4990cf09506b3b513289c2aa494dc): public server identity
protocol 1, native token login and audiobook manifest schema 2 under `/api/v1`. It does not assume that all older
BookOrbit releases provide these APIs. Upgrade the server if these endpoints are unavailable. Audio assets need
usable durations; a malformed or incomplete new book is skipped and retried, and a previously usable copy is retained.

Audio-file changes invalidate that file's cached bytes. Chapter-only changes retain downloaded audio. Server failures
do not turn an existing library into an empty library.

## Sign out

Use BookOrbit settings to sign out. This stops BookOrbit playback and removes its active library and downloads from
this device. Its owner-scoped queued sync changes remain for reconnecting to the same server and account. Your server's
books and successfully synced progress remain there. Local books are retained.
An expired login asks you to sign in again to the same server/account without discarding offline downloads.
