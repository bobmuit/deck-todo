# Vinkje

**Vinkje** ("little tick", repo `deck-todo`) is a Microsoft To Do-style Android app that stores its data in
[Nextcloud Deck](https://github.com/nextcloud/deck). Built for a small household:
same lists, same gestures as To Do, but on a self-hosted Nextcloud and usable offline.

## Installing

With [Obtainium](https://github.com/ImranR98/Obtainium): *Add app*, with this
repository's GitHub URL as the source. Obtainium installs the APK from the latest
GitHub release and offers each new one as an update. On first start the app logs in
through the browser; fill in the address of your Nextcloud server.

The APK is signed with the project's own key; the SHA-256 of its certificate is
`46:2B:6B:D4:9F:59:A5:A0:0C:3A:E1:53:A9:85:23:0E:D1:AF:4C:7C:14:20:18:74:8B:8E:69:66:F1:CE:37:10`.

## Design priority: ticking off is one tap

The main reason this app exists: in the Deck Android app, marking a card done takes
opening the card or a menu. Here it works like To Do:

- A round checkbox at the start of every row; one tap marks the task done. There is no
  dialog and you never open the card. The tap gives haptic feedback, the row animates
  into the collapsed "Voltooid" section, and a snackbar offers "Ongedaan maken".
- Tapping the circle in "Voltooid" puts the task back.
- The tick is written to the local database first and shown immediately, even offline.
  Syncing to Deck happens in the background; a slow or absent network never delays it.
- Rows show step progress ("1 van 3") so a task with steps can be judged without opening it.
- The "Taak toevoegen" bar keeps the keyboard open after Enter, so a shopping list can be
  typed in one go.

Every UI change is judged against this: if ticking off gets slower or less certain, it
is a regression.

## Mapping

| To Do | Deck |
|---|---|
| List group | Board (sharing is per board) |
| List outside a group | Stack on your own board `Lijsten`, made on first use and shown without a heading |
| List | Stack |
| Task | Card (`done`, `duedate`, assignees) |
| Important | Label `Belangrijk` |
| Note, steps, recurrence | Card description: note, `- [ ]` checkboxes, and a `---` footer with `Herhaling: <RRULE>` |
| Due date | `duedate` at 23:59 local time, so Deck web does not flag it overdue on the day itself |
| My Day, reminders | Per user, on the phone only (Room columns that are never synced) |

My Day and reminders are personal in To Do, and each of us has one phone, so they live
next to the task in the local database. A reinstall loses them; moving them into a
per-user `/.todo-app/state.json` over WebDAV is the plan if that ever matters.

## Repeating tasks and reminders

Ticking off a repeating task does what To Do does: the ticked card keeps its note and
steps but loses the rule, and a new card appears with the steps unticked and the next
due date (`domain/Recurring.kt`, computed from the old due date). The reminder moves
along by the same number of days. Undo removes the new card again.

Reminders are exact alarms (`USE_EXACT_ALARM`; this app is not on the Play Store). The
receiver checks the task is still open and still wants that time before it notifies, so
ticking, deleting or moving a reminder never has to cancel anything. The notification's
"Voltooid" button ticks the task off from the shade. Alarms are put back after a reboot,
an app update, and on every app start.

## Organising lists and groups

"Nieuwe lijst" at the bottom of the home screen makes a list outside any group; the
folder button next to it makes a group. A tap on a group heading folds it (per phone);
a long press offers a new list in it, renaming, sharing, hiding it on this phone and
deleting it. A list's menu renames, moves or deletes it. All of it works offline and
reaches Deck in the order it was done.

Deck cannot move a stack to another board, so moving a list to another group makes a
new stack there, moves the cards over one by one and deletes the old stack. A card PUT
to a stack on another board moves it; Deck carries labels over by title (creating
`Belangrijk` there if needed) and keeps assignees. The new stack's id is stored on the
queued move, so a sync cut short halfway carries on instead of starting over.

Sharing a group adds someone with edit, share and manage rights: Deck needs manage to
add or rename lists, and the two of us are equals. Finding people needs the server.

## Sharing: assigning and the other person's changes

In a shared group, a task can be assigned to anyone the board is shared with (Deck
assignees). "Aan mij toegewezen" appears among the smart lists once any group is shared.
Two phones assigning and unassigning offline merge per person.

Deck cards do not record who edited them last, so after every sync the app reads the
Deck activity stream from where it left off and keeps only the changes someone else
made: new tasks, ticked and unticked ones, edits, and tasks assigned to you. They are
collected into one notification ("Anna · Melk toegevoegd aan Boodschappen") that
opening the app clears. Nothing is posted while the app is on screen, nothing for groups
hidden on this phone, and nothing on the first sync after installing. A repeating task
ticked off elsewhere is reported once, not also as a new task. The delay is that of the
periodic sync, up to about 15 minutes.

## Importing from Microsoft To Do

`scripts/import.sh` copies To Do into Deck, once or as often as needed in the run-up to
switching. It reads To Do through Microsoft Graph, using the public client of Microsoft
Graph PowerShell and the device-code login, so there is no app registration to set up.
Logins, the plan and the state live in `~/.config/deck-todo/import/`; the login files are
readable by their owner only.

1. `scripts/import.sh microsoft-login anna` and `scripts/import.sh nextcloud-login anna --server https://cloud.example.org`.
   Both print something to open on any device, the phone included. Repeat for each
   account (`tom`, …) whose lists should come along or who owns a group.
2. `scripts/import.sh scan` writes `plan.json`: every To Do list with its counts.
   Graph does not expose list groups, so fill them in by hand. Add the groups under
   `groups` (`title`, `owner` account, `shareWith` accounts or Nextcloud display names),
   and set each list's `group` to one of them, or leave it `null` for a list outside any
   group. Lists shared with someone are skipped in everyone's plan but the owner's, so
   each is imported once. A new scan keeps these choices. A group whose title is already
   taken by a board the import did not make is skipped, so other Deck boards are left
   alone; rename the group, or set `"mergeExisting": true` to add its lists to that board.
   The loose-lists board "Lijsten" is always added to.
3. `scripts/import.sh run --dry-run` says what would happen. `run --proef` does a trial
   on boards called "zz-proef …", shared with nobody, and `remove-proef` clears them away.
4. `scripts/import.sh run` imports. Each list ends with a check: as many cards and steps in
   Deck as tasks and steps in To Do.
5. `scripts/import.sh logout anna` revokes the Nextcloud app password and deletes the
   logins.

**What comes along:** title, note (HTML from Outlook becomes text), steps, star, due date,
the repeat rule, and ticked tasks with their day. "The first Monday of the month" rules
and attachments do not; the run names those tasks. My Day is not in Graph and starts empty
anyway.

**Reminders** are personal, so they do not go on the shared card. The importer leaves the
future ones in `.deck-todo/import.json` in the user's own Nextcloud files; on the next
sync the user's app sets them and removes the file.

**Running again** adds new tasks and carries over changes and deletions in To Do. A card
someone has since changed in Deck keeps the Deck version, and anything deleted in Deck
stays deleted. `state.json` holds the To Do id → Deck id mapping that makes this work.

## Modules

- `domain/`: pure Kotlin. Description codec, recurrence rules, three-way merge.
- `deck-api/`: pure Kotlin client for the Deck REST API v1.0 (OkHttp). Shared by the app and the To Do importer. `./gradlew :deck-api:test -Plive` runs a self-cleaning spike against the real server.
- `app/`: the Android app (Compose, Material 3).
- `importer/`: command-line import from Microsoft To Do (see below). `./gradlew :importer:test -Plive` rehearses an import into the real server from a fake To Do.

## Building

```sh
nix develop          # JDK 17 + Android SDK 36
./gradlew :domain:test :app:assembleDebug
```

On NixOS the devShell points AGP at the patched `aapt2` from `androidenv`; the
build-tools version in `app/build.gradle.kts` must match the one in `flake.nix`.

### Testing on an emulator

```sh
nix develop .#emulator -c scripts/emulator.sh &     # headless, needs /dev/kvm
nix develop .#emulator -c adb install -r app/build/outputs/apk/debug/app-debug.apk
# Debug builds only: log in without the browser flow
adb push ~/.config/deck-todo/dev-credentials.json /data/local/tmp/
adb shell run-as app.vinkje sh -c 'mkdir -p files && cp /data/local/tmp/dev-credentials.json files/'
adb shell rm /data/local/tmp/dev-credentials.json
```

`./gradlew :app:installStaging` installs the minified release build next to it, signed
with the debug key and with the same emulator login (package `app.vinkje.staging`), to
check that R8 left the app working.

`airplane-mode enable|disable` via `adb shell cmd connectivity` exercises the offline path.

To test the change notifications with one account, make the debug build treat your own
changes as someone else's, then change a card through the API or Deck web:

```sh
adb shell "echo nobody | run-as app.vinkje sh -c 'cat > files/dev-notify-as'"
```

### Releasing

```sh
git tag -a v0.2.0 -m "Wat er nieuw is, in een paar regels"
git push origin v0.2.0
```

`.github/workflows/release.yml` runs the tests, builds the APK signed with the release key
and publishes it as GitHub release "Vinkje 0.2.0", with the tag message as release notes.
The version comes from the tag (versionCode = major·10000 + minor·100 + patch), so tags
must go up.

The release key is in the repository secrets `RELEASE_KEYSTORE_BASE64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`, with the
original kept offline by the maintainer. Losing it means no more updates
for installed copies: Android only accepts an update signed with the same key. For a local
release build, export `RELEASE_KEYSTORE` (path to the keystore), `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` first.

## How sync works

Every action writes the local Room database first and queues an outbox entry, so the
UI never waits for the network. A sync run (debounced after each change, on app start,
every 15 minutes, and whenever the network returns) pushes the outbox in order, then
pulls. A push whose card changed on the server since the last sync is merged three-way
(`domain/Merge.kt`): ticked steps from both phones survive. Pulls use the board-list
ETag, so a sync with no changes costs one 304.

## Disclaimer

Vinkje is an independent project. It is not affiliated with, endorsed by or sponsored by
Microsoft. Microsoft To Do is a trademark of Microsoft Corporation, used here only to
describe what the app resembles and what the importer reads from.
