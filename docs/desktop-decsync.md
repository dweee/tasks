# Desktop DecSync and Syncthing

The desktop app can read and write DecSync v2 task collections directly. DecSync
CC provides the Android side; Syncthing transfers the shared folder between the
phone and desktop. No CalDAV bridge or account on a sync server is needed.
The existing third-party sync subscription requirement applies.

## Connect a phone

1. In DecSync CC on Android, configure a DecSync folder and enable your task
   lists. Grant access to the Tasks.org provider and select those lists in Tasks.
2. Share the entire DecSync folder with the desktop using Syncthing. Allow hidden
   files, including `.decsync-info`, and wait for the initial transfer to finish.
3. On desktop, choose **Add account → DecSync folder**. Select the root folder
   containing `.decsync-info`, rather than an individual collection or writer
   directory. Existing task lists will appear after synchronization.
4. Create tasks in a DecSync list, or move existing tasks into one. Keep Syncthing
   running on both devices. Desktop checks for folder changes every 30 seconds
   while the app is open, and also syncs after local edits, on resume, and when
   you request synchronization manually. DecSync CC must also run its Android
   sync after Syncthing transfers changes.

The app supports list creation, renaming, colors, and deletion. List deletion
propagates to the other devices. **Disconnect folder** in the account settings
removes the desktop account and its local tasks while retaining the shared files.
Finish syncing pending edits before disconnecting.

Share only the DecSync folder. The desktop database, application profile,
credentials, and installation identity belong on the individual device.
Back up task data before first connecting an existing collection.

## Behavior and limitations

- Requires DecSync v2 and task collections migrated by DecSync CC. Legacy-only
  collections and unsupported versions fail without publishing pending work.
- Only explicit DecSync deletion records remove tasks or lists. A missing folder,
  missing peer file, or interrupted transfer does not imply deletion.
- Each installation uses a random writer identifier, stored outside database
  backups. Device names and network addresses are not used to identify writers.
- Writes replace files atomically and retain deletion records. The app rescans
  entry files instead of relying on the arrival order of sequence files.
- DecSync resolves resource conflicts using timestamps. Simultaneous independent
  edits to one task can require a choice of the latest resource. Existing task
  fields use the app's iCalendar merge logic; unknown iCalendar properties are
  preserved. A pending local edit survives a remote task deletion and can
  republish that task. An explicit list deletion removes the local list.
- Syncthing conflict copies are not merged automatically. Resolve these before
  continuing sync. Keep device clocks reasonably aligned.
- Only tasks are integrated; contacts and calendar events are outside this
  adapter. Syncthing pairing and Android background permissions remain managed
  by Syncthing and DecSync CC.

## Validation

Protocol tests include a wire fixture produced by the unmodified libdecsync
v2.2.1 release, its published hash vectors, separate writers, retained tombstones,
late bucket arrival, failed-write retries, and concurrent local writes. Desktop
integration tests cover task import/export, subtasks, offline changes, deletion,
and collection operations. A manual reference-library check also consumed the
desktop's updates and tombstones with libdecsync v2.2.1.

Protocol references: [DecSync v2](https://github.com/39aldo39/DecSync/blob/master/spec-v2.md),
[resource mappings](https://github.com/39aldo39/DecSync/blob/master/contacts-calendars.md),
[DecSync CC](https://github.com/39aldo39/DecSyncCC).
