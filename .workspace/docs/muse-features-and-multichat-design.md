# Muse (Meta AI phone app) — REA analysis & multi-chat design for Agent

Repo analyzed: `Muse-from-Meta_11.0.0.18.164_apkcube.apk` (package `com.facebook.aura`, "Hatch"),
via REA 6.4.0 MCP/CLI + jadx-headless-mcp 0.7.1 (JAR sha256 `6e5eacf5…573ff75`, audited in
REA's `JadxRelease.ts`). All evidence: `inspect_android_package`, `search_android_classes`,
`inspect_android_class`, `inspect_android_method` (decompiled Java, smali fallback where noted).

## 1. How Muse works (evidence)

**Identity**: `com.facebook.aura`, versionName `11.0.0.18.164`, versionCode `1061601472`,
minSdk 29, targetSdk 36, 37,292 classes. Hosts: `muse.ai`, `applink.muse.ai`, `agent.meta.ai`.
The model runs **server-side** (Meta AI cloud); the phone is the agent's body — a *gateway*
of commands and data sources.

**Layers** (all under `com.facebook.aura.*`):

| Area | Classes (evidence) | What it does |
|---|---|---|
| Model client | `nodes.HatchNodeClient` (28 methods: `attach`, `register`, `executeCommand`, `dispatchExternalCommand`, `handleInvokeRequest`, `sendInvokeResultEnvelope`, `sendPermissionCard`, `respondPermissionDenied`, `enqueueCommandWorker`), `network.transport.HatchDuplexHttpStream`, `network.acs.*` (`HatchAcsBindCvmRequestJson`, `HatchAcsBoundVm`, `FastlyOhaiAcsConfigEnvelopeJson`), `confidentialvm.*` | Duplex HTTP + GraphQL to the backend; ACS = Agent Cloud Service with CVM (confidential VM) binding + attestation configs |
| Gateway services | `gateway.service.HatchForegroundService` (`promoteToForeground`, `buildNodeWorkNotification`, `handleLeaseRefresh`), `gateway.service.NodeCommandService` (`executeAndReport`, `handleGeofenceTrigger`, `cancelAllCommands`, `clearStaleCommands`) | Foreground service keeping the agent channel alive; command execution with leases + pending-command store |
| Command system | `nodes.core.NodeCommandSpec/NodeArgSpec/NodeCommandHandler`; `commands.alarm.AlarmCommandSpecs` (12 commands: `ALARM_SET/UPDATE/DISMISS/DISMISS_ALL/LIST`, `REMINDER_SET/UPDATE/DISMISS/DISMISS_ALL/SNOOZE/LIST`); `commands.alarm.Recurrence{frequency, interval, byWeekdays, count, until}`; `commands.calendar.CalendarReminders*`; `commands.sms`, `commands.contacts`, `commands.health` | The agent's hands on the phone. `AlarmSetHandler.execute` → `AlarmHandlersKt.computeAlarmSet` → `NodeExecutionKt.deliver` response |
| Proactive triggers | `nodes.datasource.ProactiveTrigger` sealed: `AppOpen, BroadcastReceiver, CallerDriven, ContentObserver, None, NotificationListener, Periodic`; `HatchProactiveTriggerManager.createHost` (decompiled: only `ContentObserver` + `BroadcastReceiver` get live hosts); `AuraProactiveSyncManager.scheduleSync/enqueueAll` + `AuraProactiveSyncWorker.doBackgroundWork/retryOrGiveUp`; `ProactiveSyncRunner.run`; `ProactiveSnapshotSync.publishIfChanged` | Event → debounced WorkManager sync → snapshot published **only if changed**. The model sees phone-state deltas, event-driven |
| Notifications | `commands.notifications.NodeNotificationListenerService.onNotificationPosted` (decompiled: skips own package → `HatchNotificationFilterManager.shouldBlock` → `maybePublish`), `BlockedAppsNotificationFilter`, `WorkNotificationFilter`, `WorkDeviceDetector`; `notifications.push.*` (FCM + FBNS push, `AuraCvmPushDecryptor`) | NotificationListener streams filtered, debounced phone notifications to the model; push replies decrypted (CVM) |
| HITL / approvals | `nodes.core.NodeHitlSpec{access, group}`, `NodeHitlGroup` enum: `ALARMS, BATTERY, BROWSER, CALENDAR, CALL_LOG, CONTACTS, HEALTH, LOCATION, NETWORK, NOTIFICATIONS, OTHER, PHONE_CALL, PHOTOS, REMINDERS, SMS, SMS_SEND, SUBSCRIPTIONS`; `NodeHitlCatalog`, `NodePermissionCardRequest`, `agentpermission.AgentApprovalActionReceiver`, `AgentPermissionApprovalHistoryRepository` | Every gated capability is a permission card; decisions have durable history |
| Geofences | `commands.geofence.HatchGeofenceManager.registerGeofence` (decompiled: Play Services `GeofencingRequest`, initial-trigger mask `& 7`, location keep-alive foreground service), `HatchGeofenceBroadcastReceiver`, `gateway.service.GeofenceEventWorker` → `NodeCommandService.handleGeofenceTrigger` (decompiled: reads `EXTRA_GEOFENCE_ID/LABEL/TRIGGER_TYPE/LAT/LONG` → `HatchNodeOperationLease.withLease("geofence:<id>")` → command → `PendingCommandStore.remove`) | Location triggers re-registered on boot |
| Boot survival | `startup.boot.HatchBootReceiver` (manifest: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`), `startup.reminder.AlarmReregisterStartupJob`, `startup.geofence.GeofenceReregisterStartupJob`, `startup.session.HatchPrimarySessionStartupJob` | Alarms, geofences, primary session re-registered after reboot/update |
| **Multi-chat (threads)** | see section 2 | The feature ported here |
| Ideas | `explore.repo.IdeaCardsDataSource` (40 methods: `loadFeed/loadNext/executeCard/fetchCardDetail/submitFeedback/recordIdeaEngagement/shareCard/removeCard`), `IdeaCardItem{kind, tone, summary, detailDescription, previewImageUrl, selectable, buildSummary}` | Server-generated idea feed with execute + feedback + engagement |
| Goals | `library.repo.Goal/GoalNode/GoalAssociation`, `GoalMomentumStatus`, drag/reorder UI | Hierarchical goals |
| Browser takeover | `browsertask.control.BrowserControlLease`, `BrowserTaskViewer`, `AgentComputerScreen/TakeoverFooter/TerminalOutcome`, `BrowserControlConflictDialog` | Watch a remote/agent browser task, take over under a lease |
| Devices | `devices.pairing`, `partners.wearables`, `partners.voice.background.VoiceCallForegroundService` | Voice calls, WearOS companion |

## 2. Multi-chat in Muse (the ported feature) — evidence

**Data model** — one chat screen, many sessions; exactly one is *primary*:

- `gateway.HatchSessionItem{sessionId, title, snippet, status, isPrimary, isThread, isPinned,
  isExternalChannel, channel, unreadCount, createdAt, updatedAt}` — one thread row.
- `conversation.repo.SessionItemJson` adds wire fields `is_thread`, `unread_count`,
  `created_at_ms`, `updated_at_ms`.
- `conversation.api.PrimarySessionIdProvider/PrimarySessionIds` +
  `repo.HatchPrimarySessionRepository{ensureLoaded, getPrimarySessionId, setPrimarySessionId}` —
  exactly one primary session; every other chat is a *side thread*.

**Thread list** — `HatchThreadsViewModel{getSessions, getSideChatSessions, getChannelThreads,
getHasArchivedThreads, loadCachedThreadsOrEmpty, refresh, loadMore}`; repository
`HatchThreadsRepository` (23+ methods): `fetchSessions`, `applySessionUpdate` (decompiled:
moves the item between `_activeSessions` and `_archivedSessions` keyed by `isThread` +
`archived`), `archiveSession`, `deleteSession`, `moveActiveSessionToTop`,
`moveSessionBelowPinned`, `sortedForView` (decompiled: `HatchSessionListComparator` vs
`HatchArchivedSessionListComparator` by `ThreadsView.ACTIVE|ARCHIVED`), unread
(`fetchHasUnreadThreads`, `HatchUnreadThreadsRepository.hasUnreadThreads`), paging cursors
(`activeCursor`, `archivedCursor`, `_activeHasMore`).

**Thread actions** — `conversation.view.ThreadContextMenuItemsKt` fields:
`THREAD_MENU_ARCHIVE, THREAD_MENU_DELETE, THREAD_MENU_PIN, THREAD_MENU_RENAME,
THREAD_MENU_UPDATED_AT`. Server ops: `ArchiveSessionRequestJson`, `UnarchiveSessionRequestJson`,
`PinSessionRequestJson`, `UnpinSessionRequestJson`, `RenameSessionRequestJson`,
`DeleteSessionRequestJson`, `DeleteTurnRequestJson`.

**Chat screen** — `HatchConversationViewModel` (159 public methods): `sendMessage`,
`sendMessageAndFollow`, `stopGeneration`, `clearConversation`, `syncUnreadMessageCount`,
`threadItemFor` (decompiled: returns null when `sessionId == "primary_session_id"`, else finds
the row in ACTIVE then ARCHIVED thread lists), `setReplyContext`, `toggleReaction`, drafts
(`shouldPersistComposerDraft`), `startAwaitFirstResponseWatchdog`.

**Send model** — `model.HatchSendMessage{text, hiddenPrompt, imageUris, fileUris,
replyAttribution, requestId, messageMetadata, pendingDiscussionItem, extraParams,
liveSessionTargetThreadId, routeToLiveSession}` — messages carry a target session id.

**Takeaway design**: multi-chat = sessions with ids; one primary; side threads with
unread counts; pin/rename/archive/delete; newest-first ordering with pinned on top;
per-session cancel; deep-linkable thread rows.

## 3. What Agent adopts (this port)

Agent today: ONE conversation (`main` line) + ephemeral branches that fork mid-turn and
merge back. No way to start a separate chat, switch between chats, or keep a side topic
alive. Muse's model (sessions + primary + side threads) maps onto Agent's existing
`ChatDao`/`TurnRow` storage (threads already exist: "main", "branch-N") and `ChatScreen`.

Ported scope: thread list (drawer/sheet), new chat, switch, rename, pin, archive, delete,
unread badge, per-thread history isolation, per-thread model session ids. Keep branches as-is
(they're a concurrency mechanism, not chats).

---

# 4. Multi-chat design for Agent (for approval — NOT built yet)

## 4.1 What Muse does vs what Agent has

Muse (`HatchThreadsRepository`, `HatchThreadsViewModel`, `ThreadContextMenuItemsKt`,
`HatchPrimarySessionRepository`, `SessionItemJson`): one chat screen, many sessions.
Exactly one is *primary* (`threadItemFor` returns null for `"primary_session_id"`); every
other chat is a side thread with `title/snippet/status/unreadCount/isPinned/createdAt/updatedAt`.
List ops: archive / delete / pin / rename (+ `moveSessionBelowPinned`, `sortedForView` with
active vs archived comparators, paging cursors). Sends carry `session_id` (`HatchSendMessage`).
Unread tracked per thread (`HatchUnreadThreadsRepository`).

Agent today: one visible conversation (`chat_items`, unscoped). Model transcript threads exist
(`turns.thread`: `"main"`, `"branch-N"`) but are invisible internal machinery. Branches fork
mid-turn and merge back — they are concurrency, not chats. No new chat, no switching, no titles,
no archive/delete.

## 4.2 Design: chats = named threads, one active

### Data

New `chats` Room table (mirrors Muse's `SessionItemJson` + `ThreadsView.ACTIVE|ARCHIVED`):

```kotlin
@Entity(tableName = "chats")
data class ChatThread(
  @PrimaryKey val id: String,            // "main" for the existing chat; "c<8 hex>" for new ones
  val title: String,                     // first user message, <=60 chars; user-renamable
  val pinned: Boolean = false,
  val archived: Boolean = false,
  val createdAt: Long, updatedAt: Long,  // bumped on every message; list order
)
```

- `chat_items` gains `chatId: String = "main"` (default preserves every existing row).
- `turns.thread` (`TurnRow.thread`) gains the same `chatId` (already per-thread; `"main"` stays).
- Ordering (mirrors Muse's `moveActiveSessionToTop` / `sortedForView`): pinned first
  (by `updatedAt`), then active by `updatedAt` desc; archived section collapsed at the bottom.
- Unread: per-chat read mark in settings extras (`x_read_<chatId>`), reusing the existing
  global `chatRead` pattern (`MainActivity` `readMark`/`onRead`). Badge = `agent|report` items
  above the mark, same `firstUnread` rule that already skips user/activity/helper rows.

### Runtime (`AgentRuntime`, mirrors Muse's primary/side split)

- One "primary" concept already exists: `mainLine`/`mainTurn` + `"main"` transcript thread.
  Generalize: current chat id lives in `SettingsStore` (`x_chat`, default `"main"`).
- `send/admit/runTurn/loadHistory/say/mergeBranches`: replace the hard-coded `"main"`
  transcript thread with the current chat id. Branch threads become `"c.../b<item>"` so a
  side-branch forked in chat B folds back into B, never into the global line.
- Helpers are unchanged: results are delivered as notes to whichever chat started them
  (mirrors `deliverFor`/`helperBranch`; Muse's `liveSessionTargetThreadId`).
- Switching chat = active id + reload: an in-flight turn on the old chat keeps running
  (same as today's branches/routines writing while the user talks); its reply lands in its
  own chat and raises that chat's unread badge instead of the screen.
- New chat: insert row, set active, empty screen with the existing `Welcome`/morning surface.
- Subscription mode: per-chat `claude_session` (`x_session_<chatId>`) instead of one global
  `claude_session` — mirrors Muse's per-session server threads. API-key mode needs no change
  beyond the transcript thread id (already per-thread storage).
- Delete chat: remove its `chat_items` + `turns`, clear its session extras; deleting the active
  chat falls back to `"main"` (never deletable). Archive: hide from list, keep history
  (matches Muse's `ThreadsView.ARCHIVED`).

### UI (matches Muse's thread list + per-thread chat screen)

- Thread list: a drawer/sheet from the chat top bar (new icon button next to the back arrow —
  `ChatTopBar` / `ChatActions.onThreads`), showing rows with title, snippet, time, unread badge,
  pin marker; archived section at the bottom; overflow menu with
  Rename / Pin / Archive / Delete — Muse's `THREAD_MENU_*` set.
- Chat screen itself unchanged: it already renders a filtered list (`group`, quotes, branches,
  helper cards); it just receives `items.filter { it.chatId == active }`. Title in the top bar
  becomes the chat title (tappable → thread list), replacing the static agent-name-only header.
- "New chat" entry at the top of the thread list.
- Home screen: opening Chat lands on the last-active chat (stored active id); morning brief and
  routines always post to `"main"` (the primary), with unread badge — mirrors Muse's primary
  session ownership of proactive content.

### Agent tools (small surface, mirrors Muse's session ops)

- `chat_list`, `chat_new`, `chat_switch`, `chat_rename`, `chat_pin`, `chat_archive`,
  `chat_delete` — main-agent-only tools so the agent can organize chats ("start a fresh chat
  for the trip planning", "rename this chat"). Worker tools untouched.
- System prompt: one paragraph — chats are the user's separate topics; read the active chat's
  history only; start a new chat when the user changes subject, never mix topics.

### Migration

Room `fallbackToDestructiveMigration` is already the policy pre-1.0 (`Db.kt`), so schema change
is free: existing rows get `chatId = "main"`, existing turns stay on `"main"`. Seed one
`"main"` chat row ("Main chat") on first open. One-time title backfill: first user message.

### Tests (existing suites, no new infra)

- DAO/ordering unit tests (pinned-first, archived-last, unread counting) — same style as
  `ChatOpenTest`/`BranchingTest` (JUnit + Robolectric in-memory DB).
- Runtime test: send on chat A, switch to B, reply lands in A + unread badge (mirrors
  `deliverFor` branch-routing tests in `BranchingTest`).
- UI is Roborazzi screenshot (`ScreensTest`): one threads-sheet snapshot, light+dark.

### Explicitly out of scope

- Server sync / cross-device threads (Muse is server-backed; Agent is local-only, no account).
- Thread-level model picker (one roster for all chats).
- Sharing/exporting a chat (Report tool already covers long-form output).
- Merging two chats (branches already merge; chats stay separate like Muse's).
