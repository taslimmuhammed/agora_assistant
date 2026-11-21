package com.local.assistant

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.local.assistant.gallery.GalleryDatabase
import com.local.assistant.gallery.GalleryEmbedder
import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.GalleryModel
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.gallery.PhotoThumbnails
import com.local.assistant.gallery.PhotoTools
import com.local.assistant.gallery.faces.FaceFinder
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.gallery.places.PlaceList
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.assist.AssistantRole
import com.local.assistant.assist.KeepReadyService
import com.local.assistant.chat.ChatSender
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.voice.AudioFocus
import com.local.assistant.voice.Speaker
import com.local.assistant.voice.NaturalVoices
import com.local.assistant.voice.VoicePack
import com.local.assistant.voice.VoicePackInstaller
import com.local.assistant.data.prefs.SecretStore
import com.local.assistant.device.AndroidContacts
import com.local.assistant.device.AndroidPhone
import com.local.assistant.device.PermissionBroker
import com.local.assistant.memory.MemoryControls
import com.local.assistant.memory.notes.SavedImages
import com.local.assistant.memory.notes.UserNotes
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.embed.EmbedderCatalog
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.MemoryEmbedder
import com.local.assistant.memory.embed.PhotoModelEmbedder
import com.local.assistant.memory.embed.InstalledEmbedder
import com.local.assistant.memory.embed.LiteRtEmbedder
import com.local.assistant.memory.prompt.Snippet
import com.local.assistant.memory.retrieval.KotlinVectorIndex
import com.local.assistant.memory.retrieval.Retriever
import com.local.assistant.memory.retrieval.SqliteVec
import com.local.assistant.memory.retrieval.SqliteVecIndex
import com.local.assistant.memory.retrieval.VectorIndex
import com.local.assistant.memory.tools.ArchiveSearch
import com.local.assistant.memory.prompt.NoteTitle
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.prompt.MemoryContext
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.live.ServerChat
import com.local.assistant.ui.chat.ServerState
import com.local.assistant.voice.MurfVoice
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.DeviceTools
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.memory.tools.WebTools
import com.local.assistant.memory.summary.SessionSummaries
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.BackgroundJobs
import com.local.assistant.memory.work.Consolidation
import com.local.assistant.memory.work.EmbeddingQueue
import com.local.assistant.memory.work.ModelScheduler
import com.local.assistant.model.ModelManager
import com.local.assistant.model.formatBytes
import kotlinx.coroutines.flow.map
import com.local.assistant.reminders.AlarmReminderScheduler
import com.local.assistant.reminders.ClockAlarms
import com.local.assistant.reminders.PlaceFences
import com.local.assistant.reminders.PlaceReminderReceiver
import com.local.assistant.reminders.ReminderNotifications
import com.local.assistant.location.NominatimSearch
import com.local.assistant.location.PlaceFinder
import com.local.assistant.location.SavedPlaces
import com.local.assistant.device.PhoneLocation
import com.local.assistant.ui.setup.MemorySearchControls
import com.local.assistant.web.TavilySearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.local.assistant.privacy.LogText
import java.io.File
import java.time.ZonedDateTime

/**
 * Manual dependency container. The app has few enough moving parts that a DI framework would
 * cost more than it saves; swap this out if that stops being true.
 */
class AppContainer(context: Context) {

    /** Outlives any screen, so downloads and model loading survive navigation. */
    val appScope = CoroutineScope(SupervisorJob())

    /** Whether card and ID numbers, passwords and document photos are shown: not until the user authenticates. */
    val sensitiveLock = com.local.assistant.privacy.SensitiveLock(appScope)

    val settings = SettingsStore(context)

    init {
        // Where each turn's time went (files/turn-timings.csv).
        com.local.assistant.memory.prompt.TurnTimings.directory = context.filesDir
    }

    private val appContext: Context = context.applicationContext

    /** The system runs the app as the phone's assistant, which keeps it up for the power button. */
    val isAssistant: Boolean get() = AssistantRole.serviceActive(appContext)

    /** Coming to the foreground picks up what waited: family answers, photos, SMS, downloads. */
    val appForeground: AppForeground = AppForeground(
        onForeground = {
            com.local.assistant.memory.prompt.TurnTimings.event("app_back")
            backgroundJobs.onForeground()
            familyAnswers.onForeground()
            speaker.setAway(false)
            embeddingQueue.kick()
            // Started while visible, as Android requires, if the wake word or a place reminder needs it.
            KeepReadyService.update(appContext)
            resumeDownloads()
            // Fences dropped while location was off, or waiting for location all the time.
            appScope.launch { runCatching { PlaceReminderReceiver.watchAll(this@AppContainer) }.onFailure { Log.w("AppContainer", "Watching place reminders failed", it) } }
            // Photos taken meanwhile, or the first index carrying on, after a moment.
            indexPhotosIfReady(afterMs = FOREGROUND_INDEX_DELAY_MS)
            // SMS that came meanwhile, and the inbox from before once SMS access is allowed.
            smsSync.syncSoon(delayMs = FOREGROUND_INDEX_DELAY_MS)
        },
        onBackground = {
            com.local.assistant.memory.prompt.TurnTimings.event("app_away")
            backgroundJobs.onBackground()
            familyAnswers.onBackground()
            sensitiveLock.lock()
            // Out of sight, the photos wait for the charger at night, unless a big first index
            // is carrying on with its notice up.
            if (!com.local.assistant.gallery.IndexService.running) {
                synchronized(this) { autoIndex?.cancel() }
                appScope.launch { galleryIndex.pause() }
            }
            // What only the app in use needs goes back, so the home screen has less to take back:
            // the natural voice once it falls quiet, memory search once its backlog is done.
            speaker.setAway(true)
            embeddingQueue.kick()
        },
    )

    val backgroundJobs = BackgroundJobs(context)

    /** Whether sqlite-vec loads here; if not, the archive's vectors are searched in Kotlin. */
    val sqliteVecVersion: String? = SqliteVec.probe()

    private val database = AppDatabase.build(context, withVectors = sqliteVecVersion != null)

    /** How long a text is, for what of the memory a call or chat message is given. */
    val tokenEstimator = HeuristicTokenEstimator

    val memoryRepository = MemoryRepository(database)

    val chatRepository = ChatRepository(
        dao = database.chatDao(),
        sessions = SessionTracker(database.sessionDao(), appForeground),
        estimator = tokenEstimator,
        onChatsDeleted = { archive.onChatsDeleted() },
        memoryPaused = { settings.memoryPaused },
    )

    /** Downloads started and not yet here, picked up again after the app was killed. */
    private val wantedDownloads = com.local.assistant.model.WantedDownloads(context)

    /** Photo search's model, EmbeddingGemma 2; its text side is memory search's too. */
    val galleryModelManager = ModelManager(context, GalleryModel.FILE, settings::galleryModelPath, appScope, wanted = wantedDownloads)

    /** One engine for photo search and memory search (see [GalleryEmbedder]). */
    val photoModel: GalleryEmbedder = GalleryEmbedder(
        modelFile = { galleryModelManager.installed.value?.file },
        cacheDir = context.cacheDir,
        gpuAllowed = { true },
    )

    /** An embedding model the user imported from storage, used by memory search instead of the photo model. */
    val embedderManager = ModelManager(
        context = context,
        model = EmbedderCatalog.FILE,
        storedPath = settings::embedderPath,
        scope = appScope,
        onInstalled = { _, name ->
            settings.embedderKey = EmbedderCatalog.forImport(name ?: EmbedderCatalog.FILE.fileName).key
            appScope.launch {
                // The photo model's text side is memory search's no longer.
                embedder.unload()
                embeddingQueue.kick()
            }
        },
    )

    /** Memory search's model: the photo model's text side, or one the user imported. */
    val embedder: Embedder = MemoryEmbedder(
        imported = LiteRtEmbedder(
            installed = {
                settings.embedderPath?.let { path ->
                    // No key only from builds before keys were kept, when Granite was the download.
                    InstalledEmbedder(File(path), EmbedderCatalog.byKey(settings.embedderKey) ?: EmbedderCatalog.GRANITE)
                }
            },
            cacheDir = context.cacheDir,
        ),
        photo = PhotoModelEmbedder(photoModel),
        // Not the old EmbeddingGemma 300M, in the moment before dropOldEmbedder deletes it.
        useImported = { settings.embedderPath != null && settings.embedderKey != OLD_EMBEDDER_KEY },
    )

    private val vectorIndex: VectorIndex =
        if (sqliteVecVersion != null) {
            SqliteVecIndex(database)
        } else {
            KotlinVectorIndex {
                embedder.modelId?.let { id -> database.chunkDao().vectors(id).map { it.id to it.embedding } }.orEmpty()
            }
        }

    /** Every exchange, keyword-indexed and embedded, for recall across chats. */
    val archive: ArchiveRepository = ArchiveRepository(database, vectorIndex)

    /** Images the user asked to have remembered, kept outside their chats. */
    val savedImages: SavedImages = SavedImages(context.filesDir, database.noteDao(), chatRepository)

    /** The user's notes and lists, and the parts of them recall searches. */
    val userNotes: UserNotes = UserNotes(database.userNoteDao(), transaction = { block -> memoryRepository.transaction(block) })

    private val retriever = Retriever(
        facts = memoryRepository,
        archive = archive,
        embedder = embedder,
        notes = savedImages,
        userNotes = userNotes,
        // Similarities in debug builds, to calibrate the threshold on real pairs.
        logScores = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )

    val modelScheduler = ModelScheduler()

    val photoLibrary = PhotoLibrary(context)

    val photoThumbnails = PhotoThumbnails(photoLibrary)

    private val galleryDatabase = GalleryDatabase.build(context)

    /** Towns, states and countries, offline, for "photos in Munnar". */
    val placeList = PlaceList(context.assets)

    /** The people in the photos; nothing is looked for until "Find people" is turned on. */
    val faceIndex = FaceIndex(
        database = galleryDatabase,
        library = photoLibrary,
        newFinder = { FaceFinder(context.assets) },
        enabled = { settings.findPeople },
        userName = { memoryRepository.coreFacts().firstOrNull { it.subject == "user" && it.attribute == "name" }?.value },
        groupedBy = { settings.faceGroupingVersion },
        setGroupedBy = { settings.faceGroupingVersion = it },
    )

    /** People named from contacts' photos, once faces are found (`gallery/faces/ContactNames`). */
    val contactNames = com.local.assistant.gallery.faces.ContactNames(
        database = galleryDatabase,
        faces = faceIndex,
        photos = com.local.assistant.gallery.faces.AndroidContactPhotos(context),
        enabled = { settings.findPeople },
    )

    val galleryIndex = GalleryIndex(
        dao = galleryDatabase.photoDao(),
        library = photoLibrary,
        embedder = photoModel,
        scheduler = modelScheduler,
        scope = appScope,
        faces = faceIndex,
        places = placeList,
        onDocuments = { photoThumbnails.documents.value = it },
        afterFaces = { contactNames.update() },
        heat = ::thermalStatus,
    )

    /** `PowerManager.THERMAL_STATUS_*`, or none before Android 10. */
    fun thermalStatus(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) appContext.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0 else 0

    private var autoIndex: kotlinx.coroutines.Job? = null

    /**
     * Indexes the photos, and with Find people their faces, without waiting for the Photos
     * screen: as soon as the photo model is here and photos may be read, while the app is in
     * front. [afterMs] lets coming to the front settle first.
     */
    fun indexPhotosIfReady(afterMs: Long = 0) {
        if (!photoSearchReady()) return
        synchronized(this) {
            autoIndex?.cancel()
            autoIndex = appScope.launch {
                kotlinx.coroutines.delay(afterMs)
                if (!appForeground.isForeground) return@launch
                galleryIndex.start()
                // A big backlog (a new install, Find people just turned on) carries on after the
                // app is closed, with a notice; started now, while the app is in front, as
                // Android requires.
                if (com.local.assistant.gallery.IndexService.running) return@launch
                val status = kotlinx.coroutines.withTimeoutOrNull(30_000L) { galleryIndex.status.first { it.total > 0 } } ?: return@launch
                faceIndex.refreshStatus()
                val facesLeft = if (faceIndex.isOn) faceIndex.status.value.photosLeft else 0
                if (maxOf(status.remaining, facesLeft) >= com.local.assistant.gallery.IndexService.BACKLOG && appForeground.isForeground) {
                    com.local.assistant.gallery.IndexService.start(appContext)
                }
            }
        }
    }

    init {
        // Which photos to keep hidden, from the index, before any is shown.
        appScope.launch { galleryIndex.refreshDocuments() }
    }

    /** find_photos is offered only once its model is here and the photos may be read. */
    fun photoSearchReady(): Boolean =
        galleryModelManager.installed.value != null && photoLibrary.access() != PhotoLibrary.Access.NONE

    /** Phones this small give the photo model's memory back (what memory search isn't using) as soon as the Photos screen closes. */
    val isSmallDevice: Boolean = totalMemoryBytes(context) <= SMALL_DEVICE_BYTES

    /** Secrets the user pastes in, encrypted with a Keystore key. */
    val secrets = SecretStore(context)

    /** "Report this reply": to the developer's Google Form (see ReplyReporter). */
    val replyReporter = com.local.assistant.chat.ReplyReporter()

    /** "0.3.0 (5), Luna's server": what a report says it came from. */
    val appVersion: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}), Luna's server"
    }.getOrDefault("Luna's server")

    /** Tavily, with the user's own key; off (and not offered) until there is one. */
    val webSearch = TavilySearch(key = { secrets.get(SecretStore.TAVILY_KEY) })

    /** Health Connect, read only: steps, heart rate, sleep and workouts the phone's health apps keep. */
    val healthSource = com.local.assistant.health.HealthConnectSource(context)

    /** health_data: declared wherever there's a Health Connect, asking for access the first time each kind is asked about. */
    val healthTools = com.local.assistant.health.HealthTools(
        healthSource,
        requestAccess = { asked -> permissions.requestHealth(asked) },
        appName = { packageName ->
            runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        },
        stepGoal = { settings.stepGoal },
    )

    /** What a call or chat message is told of the user: profile, summary, agenda, notes (live/LiveContext). */
    val memoryContext = MemoryContext(
        chats = chatRepository,
        memory = memoryRepository,
        estimator = tokenEstimator,
        noteIndex = { userNotes.all().map { NoteTitle(it.id, it.title, it.kind == NoteKind.LIST) } },
    )

    val embeddingQueue: EmbeddingQueue = EmbeddingQueue(
        archive = archive,
        embedder = embedder,
        scheduler = modelScheduler,
        scope = appScope,
        inForeground = { appForeground.isForeground },
        notes = database.noteDao(),
        userNotes = database.userNoteDao(),
    )

    /** A note changed on the Notes screen: its new parts need vectors. */
    fun notesChanged() {
        embeddingQueue.kick()
    }

    /** Geofences for place reminders: Play Services' where there are, else Android's own. */
    val placeFences = PlaceFences.forPhone(context)

    val reminderScheduler = AlarmReminderScheduler(context, placeFences)

    /** Alarms in the phone's own clock app, for "wake me up at 6". */
    val clockAlarms = ClockAlarms(context, inForeground = { appForeground.isForeground })

    /** Runtime permissions asked for from outside the UI, like contacts on the first "call amma". */
    val permissions = PermissionBroker(context)

    /**
     * Where place reminders ring: "here", home and office, and towns on the phone; shops and
     * addresses on OpenStreetMap, sent only their name and the user's rough area.
     */
    val placeFinder = PlaceFinder(
        search = NominatimSearch(),
        towns = { placeList.get() },
        location = PhoneLocation(context, permissions),
        own = SavedPlaces(
            memory = memoryRepository,
            get = { database.appStateDao().get(it) },
            put = { key, value -> database.appStateDao().put(com.local.assistant.memory.db.AppStateEntity(key, value)) },
        ),
    )

    /** Calls, messages, timers, apps, phone settings and arithmetic. */
    private val deviceTools = DeviceTools(
        phone = AndroidPhone(context, inForeground = { appForeground.isForeground }),
        contacts = AndroidContacts(context, permissions),
        people = memoryRepository,
        alarms = clockAlarms,
        now = ZonedDateTime::now,
        family = { to, text -> sendThroughLuna(to, text) },
    )

    /** Files on the phone by name: read_file, declared once All files access is given. */
    val fileFinder = com.local.assistant.files.FileFinder(context)

    fun fileSearchReady(): Boolean = com.local.assistant.files.FileAccess.granted(appContext)

    private val fileTools = com.local.assistant.files.FileTools(
        find = { asked, type, period -> fileFinder.find(asked, type, period) },
        folders = { asked -> fileFinder.folders(asked) },
        read = { found -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.local.assistant.files.DocumentFiles.read(context, found) } },
        now = ZonedDateTime::now,
        ready = ::fileSearchReady,
        attached = { chatId ->
            chatRepository.messagesFor(chatId)
                .filter { it.attachmentKind == com.local.assistant.data.db.AttachmentKind.DOCUMENT && it.attachmentPath?.let(::File)?.isFile == true }
                .map { com.local.assistant.memory.tools.AttachedDocument(it.attachmentPath!!, com.local.assistant.media.AttachmentStore.documentName(it.attachmentPath)) }
        },
    )

    /** Messages that came to the phone, kept for search (check_inbox) as long as chats are. */
    val inbox: com.local.assistant.inbox.Inbox = com.local.assistant.inbox.Inbox(
        dao = com.local.assistant.inbox.InboxDatabase.build(context).dao(),
        scope = appScope,
        keeping = { settings.keepMessages },
        ignoredApps = { settings.inboxIgnoredApps },
        retentionDays = { settings.historyRetentionDays },
        smsApp = { smsSync.smsApp() },
        readsSmsInbox = { smsSync.allowed() },
    )

    /** The SMS inbox read into [inbox], while the app is the phone's assistant. */
    val smsSync: com.local.assistant.inbox.SmsSync = com.local.assistant.inbox.SmsSync(
        context = context,
        inbox = inbox,
        scope = appScope,
        keeping = { settings.keepMessages },
        isAssistant = { com.local.assistant.assist.AssistantRole.isDefault(context) },
        retentionDays = { settings.historyRetentionDays },
        lastId = { settings.smsReadUpTo },
        setLastId = { settings.smsReadUpTo = it },
    )

    /** check_inbox is offered: messages are kept, and notification access is allowed or SMS are read. */
    fun inboxReady(): Boolean = settings.keepMessages && (com.local.assistant.away.MessageListener.allowed(appContext) || smsSync.allowed())

    val toolExecutor = ToolExecutor(
        store = memoryRepository,
        reminders = reminderScheduler,
        alarms = clockAlarms,
        log = ChatToolLog(chatRepository),
        now = ZonedDateTime::now,
        archive = ArchiveSearch { query, limit ->
            // Their own notes first, as they are now: an older conversation about a list is out of date once it changes.
            val notes = retriever.searchNotes(query, limit).map { note ->
                val kind = if (note.kind == NoteKind.LIST) "list" else "note"
                val content = if (note.kind == NoteKind.LIST) {
                    note.items.joinToString("; ") { if (it.done) "${it.text} (ticked)" else it.text }.ifEmpty { "empty" }
                } else {
                    note.body.ifBlank { "empty" }
                }
                Snippet("The user's $kind “${note.title}” ($kind ${note.id}), as it is now: $content", note.updatedAt)
            }
            val images = retriever.searchImages(query, limit).map {
                Snippet("Saved image “${it.title}”: ${it.details} (the user can ask about it and you will see it again)", it.createdAt)
            }
            (notes + images + retriever.search(query, limit).map { Snippet(it.chunk.text, it.chunk.createdAt) }).take(limit)
        },
        memoryPaused = { settings.memoryPaused },
        images = savedImages,
        device = deviceTools,
        web = WebTools(webSearch),
        photos = PhotoTools(
            galleryIndex,
            ready = ::photoSearchReady,
            faces = faceIndex,
            findPeople = { settings.findPeople },
            places = placeList,
            // Sharp enough to read a certificate's marks; one file, replaced by the next question.
            readable = { mediaId ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    photoLibrary.jpeg(mediaId, PHOTO_READ_PIXELS)?.let { bytes -> java.io.File(context.cacheDir, "photo-question.jpg").apply { writeBytes(bytes) }.absolutePath }
                }
            },
            mayRead = { context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true },
            documents = { field -> fileTools.idNumber(field) },
        ),
        health = healthTools,
        places = placeFinder,
        notes = userNotes,
        files = fileTools,
        inbox = com.local.assistant.inbox.InboxTools(inbox, now = ZonedDateTime::now, ready = ::inboxReady),
    )

    /** An exchange is stored, reply and all, typed or said in a live call: archived for recall. */
    private suspend fun exchangeStored(userMessageId: Long) {
        // A saved image from this turn needs a vector too, so kick either way.
        archive.recordExchange(userMessageId)
        embeddingQueue.kick()
    }

    /**
     * Live voice (live/): Luna's server, from Settings or the build's local.properties. Without
     * one, the live entry points stay hidden and everything runs on the phone as before.
     */
    val liveApi = com.local.assistant.live.LiveApi(
        serverUrl = { settings.liveServerUrl ?: BuildConfig.LIVE_SERVER_URL },
        appKey = { secrets.get(SecretStore.LIVE_APP_KEY) ?: BuildConfig.LIVE_APP_KEY },
    )

    /** The phone's tools as a call or chat message is given them: those set up now. */
    private fun liveDeclarations() =
        ToolCatalog.declarations(web = webSearch.enabled, photos = photoSearchReady(), health = healthTools.available(), files = fileSearchReady(), inbox = inboxReady())

    private fun liveRules() =
        ToolCatalog.rules(web = webSearch.enabled, photos = photoSearchReady(), health = healthTools.available(), files = fileSearchReady(), inbox = inboxReady())

    /** What the phone shares with Luna's server for one call or chat message. */
    private val liveContexts = com.local.assistant.live.LiveContextBuilder(
        memory = memoryContext::liveContext,
        declarations = ::liveDeclarations,
        rules = ::liveRules,
        notes = userNotes::all,
        health = healthTools::briefing,
        userName = { memoryControls.profile()["name"]?.trim()?.takeIf { it.isNotEmpty() } },
        murfVoice = { settings.murfVoice },
    )

    /** Live calls with Luna, and the one in progress. */
    val liveCalls = com.local.assistant.live.LiveCalls(
        context = appContext,
        api = liveApi,
        contexts = liveContexts,
        execute = toolExecutor::execute,
        chats = chatRepository,
        onExchangeStored = ::exchangeStored,
        appScope = appScope,
        enabled = { settings.liveVoice },
        userId = { settings.liveUserId },
    )

    /** The chat's replies, from Luna's server: the brain of her calls, with the phone's tools. */
    val serverChat = ServerChat(
        serverUrl = { settings.liveServerUrl ?: BuildConfig.LIVE_SERVER_URL },
        appKey = { secrets.get(SecretStore.LIVE_APP_KEY) ?: BuildConfig.LIVE_APP_KEY },
        contexts = liveContexts,
        execute = toolExecutor::execute,
        history = chatRepository::messagesFor,
        userId = { settings.liveUserId },
    )

    /** One typed or spoken message: the phone's own commands here, the rest by [serverChat]. */
    val turnRunner = TurnRunner(
        tools = toolExecutor,
        server = serverChat,
        serverAvailable = liveCalls::available,
        serverConfigured = liveCalls::visible,
        onExchangeStored = ::exchangeStored,
    )

    /** Luna's check-in calls at the doses' times, while live voice is on. */
    val checkIns = com.local.assistant.live.CheckIns(appContext, settings, liveApi, enabled = liveCalls::visible)

    /** What the family answers on WhatsApp, told to her outside a call too. */
    val familyAnswers = com.local.assistant.live.FamilyAnswers(
        context = appContext,
        settings = settings,
        api = liveApi,
        chats = chatRepository,
        speak = { speaker.speakOffline(it) },
        enabled = liveCalls::visible,
        inCall = { liveCalls.current.value?.isOver == false },
        inSight = { appForeground.isForeground },
        scope = appScope,
    )

    /**
     * A chat's message to someone in the family hub, sent from Luna's WhatsApp as a call sends it,
     * so the answer comes back; null (WhatsApp opens on the phone instead) when they aren't in it,
     * or live voice is off or offline.
     */
    private suspend fun sendThroughLuna(to: String, text: String): String? {
        if (!liveCalls.available()) return null
        return try {
            liveApi.messageFamily(to, text).sentTo?.also { familyAnswers.expect() }
        } catch (e: com.local.assistant.live.LiveError) {
            Log.i("AppContainer", "Not sent from Luna's WhatsApp, so WhatsApp opens instead: ${e.message}")
            null
        }
    }

    val attachmentStore = AttachmentStore(context)

    val sessionSummaries = SessionSummaries(database.sessionDao())

    /** What the memory screen can see and change. */
    val memoryControls = MemoryControls(
        database = database,
        memory = memoryRepository,
        archive = archive,
        chats = chatRepository,
        reminders = reminderScheduler,
        onChanged = {},
        images = savedImages,
        townAt = { placeList.get().nearest(it.lat, it.lon)?.town },
    )

    val consolidation = Consolidation(
        embeddings = embeddingQueue,
        sessions = sessionSummaries,
        memory = memoryRepository,
        database = database,
        retentionDays = { settings.historyRetentionDays },
        applyRetention = { days ->
            memoryControls.applyRetention(days).also {
                if (it > 0) attachmentStore.pruneExcept(chatRepository.attachmentPaths())
            }
        },
        releaseModel = {
            if (!appForeground.isForeground) embedder.unload()
        },
        pruneSavedImages = savedImages::prune,
        finishPastReminders = { memoryControls.finishPastReminders() },
        tidyInbox = {
            smsSync.sync()
            inbox.purge()
        },
    )

    /**
     * The Images tab's "+": Luna's server looks at the picked image, as "remember this" in a chat
     * does, and it is kept with the title and details written. Null when the server can't.
     */
    suspend fun addSavedImage(uri: Uri): NoteEntity? {
        val picked = attachmentStore.importImage(uri)
        try {
            val described = try {
                serverChat.describe(picked.absolutePath)
            } catch (e: com.local.assistant.live.LiveError) {
                Log.w("AppContainer", "The image wasn't described: ${e.message}")
                return null
            }
            return savedImages.saveUpload(described.title, described.details, picked.absolutePath)
                .also { embeddingQueue.kick() }
        } finally {
            attachmentStore.delete(picked.absolutePath)
        }
    }

    /** "Forget everything". */
    suspend fun forgetEverything() = memoryControls.forgetEverything()

    val audioRecorder = AudioRecorder(appScope)

    /** Voice messages written down as they're recorded, by the phone's own recogniser. */
    val transcriber = com.local.assistant.voice.Transcriber(context, appScope)

    /** Sends messages and streams replies for the chat and the assistant overlay alike. */
    val chatSender = ChatSender(chatRepository, turnRunner, appScope)

    /** The natural voices' downloads (Settings › Voice), for speaking offline. Once one arrives, it's the offline voice. */
    val voicePacks: List<VoicePackInstaller> = VoicePack.ALL.map { pack ->
        VoicePackInstaller(context, pack, appScope, onInstalled = { settings.naturalVoice = NaturalVoices.firstOf(pack).id }, wanted = wantedDownloads)
    }

    fun voicePack(pack: VoicePack): VoicePackInstaller = voicePacks.first { it.pack == pack }

    /**
     * The assistant's voice: Murf's, through Luna's server, while it can be reached; offline the
     * chosen natural voice when it's here, else the phone's text-to-speech.
     */
    val speaker = Speaker(
        context,
        settings,
        installed = { pack -> voicePack(pack).installed.value },
        murf = { fallback, onSpeakingChanged ->
            MurfVoice(
                speech = liveApi::speech,
                chosen = { settings.murfReplies && liveCalls.available() },
                cacheDir = context.cacheDir,
                scope = appScope,
                fallback = fallback,
                onSpeakingChanged = onSpeakingChanged,
            )
        },
    )

    val audioFocus = AudioFocus(context)

    /** The chat screen's own hold on other audio, so it and the overlay never release each other's. */
    val chatAudioFocus = AudioFocus(context)

    /** "While you were away": notifications kept for the brief. */
    val awayBrief = com.local.assistant.away.AwayBrief(
        context = context,
        settings = settings,
        dao = com.local.assistant.away.AwayDatabase.build(context).dao(),
        replies = com.local.assistant.away.ReplyActions(),
        scope = appScope,
    )

    private var resuming: kotlinx.coroutines.Job? = null

    /**
     * Pause: in setup, on the download's notification, or Stop in Task Manager. Every download
     * stops where it is, still wanted, with what has arrived kept, and nothing is picked up by
     * itself (as the app comes to the front, or Android runs the job again) until one is started
     * again, which carries on the rest too ([resumeDownloads]).
     */
    fun pauseDownloads() {
        wantedDownloads.pause(true)
        resuming?.cancel()
        listOf(galleryModelManager, embedderManager).forEach { it.pause() }
        voicePacks.forEach { it.pause() }
        Log.i("AppContainer", "Downloads paused")
    }

    /** Downloading is paused ([pauseDownloads]). */
    val downloadsPaused: kotlinx.coroutines.flow.StateFlow<Boolean> get() = wantedDownloads.paused

    /** A download started and not finished, which [resumeDownloads] would pick up. */
    fun downloadsPending(): Boolean =
        galleryModelManager.shouldResume() || voicePacks.any { it.shouldResume() }

    /**
     * Picks up the downloads that were started and not finished: the phone killed the app (it
     * does, swiped from the recent apps), the connection dropped, or the last step (the photo
     * model's check, the voice's unpack, minutes without traffic) was frozen. One at a time. As
     * the app comes to the front, and as Android runs the download job again (see
     * BackgroundDownloads). True when there's one to pick up.
     */
    fun resumeDownloads(): Boolean {
        val voices = voicePacks.filter { it.shouldResume() || it.isBusy }
        val models = listOf(galleryModelManager).filter { it.shouldResume() || it.isBusy }
        if (voices.isEmpty() && models.isEmpty()) return false
        if (resuming?.isActive == true) return true
        resuming = appScope.launch {
            for (manager in models) {
                if (manager.shouldResume()) manager.download()
                manager.awaitIdle()
            }
            for (installer in voices) {
                if (installer.shouldResume()) installer.download()
                installer.awaitIdle()
            }
        }
        return true
    }

    /**
     * EmbeddingGemma 300M was memory search's own model until the photo model took it over
     * (2026-11-07). Its file and the runtime's cache of it are deleted, about 440 MB; its
     * vectors are replaced as the archive is embedded again.
     */
    private suspend fun dropOldEmbedder() {
        if (settings.embedderKey != OLD_EMBEDDER_KEY) return
        embedderManager.deleteInstalled()
        settings.embedderKey = null
        dropEmbedderCaches()
        Log.i("AppContainer", "Deleted EmbeddingGemma 300M; memory search uses the photo model")
    }

    /** The runtime's caches of the imported embedder, named after its file. */
    private fun dropEmbedderCaches() {
        appContext.cacheDir.listFiles { file -> file.name.startsWith(EmbedderCatalog.FILE.fileName) }?.forEach { it.delete() }
    }

    fun memorySearchControls() = MemorySearchControls(
        photoModel = galleryModelManager,
        photoModelName = "EmbeddingGemma 2",
        photoModelBytes = GalleryModel.SIZE_BYTES,
        imported = embedderManager,
        importedName = { EmbedderCatalog.byKey(settings.embedderKey)?.displayName },
        remaining = embeddingQueue.remaining,
        deleteImported = {
            embedder.unload()
            embedderManager.deleteInstalled()
            settings.embedderKey = null
            dropEmbedderCaches()
            embeddingQueue.kick()
        },
    )

    /** The download under way, if any, as its notification tells it: one at a time is shown. */
    private fun downloads(): kotlinx.coroutines.flow.Flow<com.local.assistant.model.DownloadState?> {
        fun of(name: String, transfer: com.local.assistant.model.Transfer?) = transfer?.let {
            val verb = if (it.kind == com.local.assistant.model.TransferKind.IMPORT) "Copying" else "Downloading"
            com.local.assistant.model.DownloadState(
                title = "$verb $name",
                fraction = it.fraction.takeIf { _ -> it.bytesTotal > 0 },
                detail = if (it.checking) "Checking the download" else "${formatBytes(it.bytesDone)} of ${formatBytes(it.bytesTotal)}",
                finishing = it.checking,
            )
        }
        val models = kotlinx.coroutines.flow.combine(
            galleryModelManager.transfer,
            embedderManager.transfer,
        ) { photo, imported ->
            of("the photo model", photo) ?: of("the memory search model", imported)
        }
        val voices = kotlinx.coroutines.flow.combine(voicePacks.map { installer -> installer.progress.map { installer.pack to it } }) { all ->
            all.firstNotNullOfOrNull { (pack, progress) ->
                when (progress) {
                    is VoicePackInstaller.Progress.Downloading -> of("the ${pack.title} voice", progress.transfer)
                    is VoicePackInstaller.Progress.Unpacking ->
                        com.local.assistant.model.DownloadState("Unpacking the ${pack.title} voice", progress.fraction, "Almost there", finishing = true)
                    null -> null
                }
            }
        }
        return kotlinx.coroutines.flow.combine(models, voices) { model, voice -> model ?: voice }
    }

    /**
     * How many times the phone closed the app in the last day, when that is often enough to tell
     * the user (once: [SettingsStore.toldAboutPhoneKills]) and the app is the phone's assistant,
     * which the closes hurt most. Null otherwise. Counted as each process starts, which is just
     * after such a close.
     */
    val phoneKills = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** The user answered the note about [phoneKills]: it doesn't come back. */
    fun toldAboutPhoneKills() {
        settings.toldAboutPhoneKills = true
        phoneKills.value = null
    }

    init {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val kills = com.local.assistant.assist.ProcessExits.systemKillsSince(appContext, System.currentTimeMillis() - 24 * 60 * 60_000L)
            com.local.assistant.memory.prompt.TurnTimings.event("process_start", detail = "$kills system kills in 24 h")
            if (kills >= com.local.assistant.assist.ProcessExits.WORTH_A_NOTICE && !settings.toldAboutPhoneKills && isAssistant) phoneKills.value = kills
        }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { deleteRetiredChatModel() }
        com.local.assistant.model.BackgroundDownloads.watch(appContext, appScope, downloads(), resume = ::resumeDownloads, pending = ::downloadsPending, pause = ::pauseDownloads)
        appScope.launch {
            dropOldEmbedder()
            // Memory search starts once the photo model is here (downloaded later, or already),
            // and so does indexing the photos.
            galleryModelManager.installed.collect {
                if (it != null) {
                    embeddingQueue.kick()
                    indexPhotosIfReady()
                }
            }
        }
        backgroundJobs.scheduleNightly()
        // Check-in alarms don't survive a restart or an update; the kept doses set them again.
        checkIns.schedule()
        // Exchanges from before the archive existed, or missed by a crash, then their vectors.
        appScope.launch {
            val finished = memoryControls.finishPastReminders()
            if (finished > 0) android.util.Log.i("AppContainer", "Finished $finished reminders whose time had passed")
            val dropped = archive.dropUtilityExchanges()
            if (dropped > 0) android.util.Log.i("AppContainer", "Took $dropped sums, phone commands and photo searches out of the archive")
            val archived = archive.backfill(beforeId = archive.lastMessageId() + 1)
            if (archived > 0) android.util.Log.i("AppContainer", "Archived $archived earlier exchanges")
            embeddingQueue.kick()
        }
    }

    /**
     * Gemma 4 E4B, the on-phone chat model of earlier builds, and LiteRT-LM's caches of it: 3.66 GB
     * or more, unused since replies come from Luna's server. Only ever inside the app's own files.
     */
    private fun deleteRetiredChatModel() {
        val path = settings.takeRetiredChatModel() ?: return
        val file = File(path)
        if (!file.canonicalPath.startsWith(appContext.filesDir.canonicalPath + File.separator)) return
        val freed = file.length()
        if (file.delete()) Log.i("AppContainer", "Deleted the retired chat model, ${formatBytes(freed)}")
        appContext.cacheDir.listFiles { cached -> cached.name.startsWith(file.name) }?.forEach { it.delete() }
    }

    /** Whether the chat's replies can come from Luna's server now. */
    fun serverState(): ServerState = when {
        liveCalls.available() -> ServerState.READY
        liveCalls.visible() -> ServerState.OFFLINE
        else -> ServerState.NONE
    }

    private companion object {
        /** The longest side of a found photo kept for reading. */
        const val PHOTO_READ_PIXELS = 1024

        /** At or below this, the photo model's memory goes back as soon as the Photos screen closes. */
        const val SMALL_DEVICE_BYTES = 8L * 1024 * 1024 * 1024

        /** After coming to the front, how long photo indexing waits for the app to settle. */
        const val FOREGROUND_INDEX_DELAY_MS = 10_000L

        /** EmbeddingGemma 300M's key, from before the photo model did memory search too. */
        const val OLD_EMBEDDER_KEY = "embeddinggemma-300m-wi8"

        fun totalMemoryBytes(context: Context): Long {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return info.totalMem
        }
    }
}

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LogText.showsUserText = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appForeground)
        // The screen going off hides shown details too, even with the app (or the panel) in front.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    if (intent.action == android.content.Intent.ACTION_SCREEN_OFF) container.sensitiveLock.lock()
                }
            },
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply { addAction(android.content.Intent.ACTION_SCREEN_ON) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Location back on: Play Services dropped every geofence when it went off, and they were
        // set again only when the app next came to the front. The user turns location off and on
        // during the day (13:20 off, 16:46 on, 17:07 off on 9 Nov 2026), so a reminder could go
        // unwatched for hours. Android tells this only to a running app, which the assistant
        // service, or KeepReadyService while a place reminder waits, keeps it.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    if (context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != true) return
                    container.appScope.launch {
                        runCatching { PlaceReminderReceiver.watchAll(container) }
                            .onSuccess { Log.i("AssistantApplication", "Location back on: watching $it place reminders again") }
                            .onFailure { Log.w("AssistantApplication", "Watching place reminders again failed", it) }
                    }
                }
            },
            android.content.IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ReminderNotifications.createChannel(this)
    }

    /**
     * Out of sight with the system asking for memory back: the thumbnails, the voices and memory
     * search's model go, and come back when next wanted.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.local.assistant.memory.prompt.TurnTimings.event("trim_memory", detail = "level $level")
        val background = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (background) container.photoThumbnails.clear()
        if (background && !container.appForeground.isForeground && !container.chatSender.isBusy) {
            container.speaker.release()
            container.appScope.launch { container.embedder.unload() }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AssistantApplication).container
