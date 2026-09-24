package com.ambokit.aep.rockdodge.tv

import android.app.Activity
import android.util.Log
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.view.WindowManager
import com.ambokit.aep.core.*
import com.ambokit.aep.host.ambokit.EmbeddedAndroidAmboKitHost
import com.ambokit.aep.host.tv.AndroidTvExperienceHost
import com.ambokit.aep.rockdodge.RockDodgeEngine
import com.ambokit.aep.rockdodge.RockDodgeConfig
import org.json.JSONObject

class MainActivity : Activity(), AepHostListener, PlayerActionListener {

    private lateinit var gameView: RockDodgeView
    private lateinit var host: EmbeddedAndroidAmboKitHost
    private lateinit var session: AepSession
    private lateinit var tvHost: AndroidTvExperienceHost
    private lateinit var game: RockDodgeEngine
    private val liveDecodeExecutor = Executors.newSingleThreadExecutor()
    private val latestLiveFrame = AtomicReference<AepLivePersonFrame?>(null)
    private val latestPoseFrame = AtomicReference<AepPoseFrame?>(null)
    private val latestMotionState = AtomicReference<PlayerMotionState?>(null)
    private val liveDecodeRunning = AtomicBoolean(false)
    private val poseRigMapper = PoseRigMapper(mirrorLocalX = true)

    // Do not start Rock Dodge the instant calibration finishes. A short tracking-lock
    // window lets the player see that the avatar is already following them before hazards begin.
    private var poseSyncFrames = 0
    private var poseSyncReady = false
    private var readyCountdownStartedAtMs = 0L
    private val requiredPoseSyncFrames = 18
    private val readyCountdownMs = 1_500L

    // When the last connection transition arrived, so each one can be logged with the gap since
    // the one before it. The reconnect path is the thing AepConnectionState was added for and it
    // is the one thing here that is only ever observed by eye.
    private val lastConnectionChangeMs = AtomicLong(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        game = RockDodgeEngine(loadCanonicalRockDodgeConfig())

        gameView = RockDodgeView(this).also { view ->
            view.onRestartRequested = {
                if (::session.isInitialized && session.state == AepState.COMPLETED) {
                    // Restart only the experience. Keep the existing AmboKit session,
                    // Companion connection, pose capability and calibration alive.
                    session.restartExperience()
                    view.statusText = "Restarting…"
                    view.invalidate()
                }
            }
        }
        setContentView(gameView)

        host = EmbeddedAndroidAmboKitHost(
            requestLivePerson = true
        )
        session = Aep.start(
            AepExperienceDefinition(
                id = "com.ambokit.aep.rockdodge",
                capabilities = listOf("camera.pose", "livevideo.person")
            ),
            host,
            poseConfig = PoseInteractionConfig(mirrorHorizontal = true)
        ).also {
            it.actionListener = this
            it.hostEventListener = this
            // The supported way to learn that the phone dropped. This used to be read off the raw
            // host events below, which worked and is exactly why nobody noticed AepSession had no
            // way to report it - a sample reaching through the escape hatch hides the gap it is
            // working around. AEP reports the link's health and leaves the response to us.
            it.connectionChanged += { connection ->
                // Logged here rather than inside runOnUiThread so the timestamp is when the
                // event arrived, not when the main thread got round to drawing the banner -
                // which on a television is a difference worth being able to see.
                //
                // Until this existed the reconnect sequence left no trace at all. The banner is
                // drawn and nothing is recorded, so a device pass that failed had nothing to
                // diagnose from and a device pass that passed produced no numbers. The two gaps
                // that matter - how long from the phone dropping to the banner, and from the
                // phone returning to play resuming - are both in here.
                val now = SystemClock.elapsedRealtime()
                val previous = lastConnectionChangeMs.getAndSet(now)
                val gap = if (previous == 0L) "first" else "+${now - previous}ms"
                Log.i("AEP", "connection: $connection ($gap) state=${it.state} " +
                    "calibrated=${it.interpreter != null}")
                runOnUiThread {
                    gameView.connectionState = connection
                    gameView.statusText = when (connection) {
                        AepConnectionState.CONNECTING -> "Creating Ambo session…"
                        AepConnectionState.CONNECTED ->
                            if (it.interpreter == null) "Waiting for player / calibrating…" else "Ready"
                        AepConnectionState.RECONNECTING -> "Reconnecting…"
                        AepConnectionState.REJOIN_REQUIRED -> "Creating a new AmboJoin…"
                        AepConnectionState.CLOSED ->
                            if (it.state == AepState.FAULTED) "Ambo session failed" else "Ambo session closed"
                    }
                    gameView.invalidate()
                }
            }
            it.motionListener = PlayerMotionListener { motion ->
                // Conflate motion exactly like live video: the game consumes only the newest
                // player state on the next TV frame. Never replay queued/stale movement.
                latestMotionState.set(motion)
            }
        }
        tvHost = AndroidTvExperienceHost(session) { deltaMs ->
            // Consume only the freshest pose/motion state once per display frame. This keeps
            // the avatar, game collision position and current camera pose in the same time domain.
            latestMotionState.getAndSet(null)?.let { game.onPlayerMotion(it) }
            latestPoseFrame.getAndSet(null)?.let { frame ->
                gameView.poseFrame = frame
                val rig = poseRigMapper.update(frame)
                if (rig != null) gameView.poseCharacterState = rig

                if (session.interpreter != null && frame.trackingState == AepTrackingState.TRACKED && rig?.visible == true) {
                    if (!poseSyncReady) {
                        poseSyncFrames += 1
                        poseSyncReady = poseSyncFrames >= requiredPoseSyncFrames
                        if (poseSyncReady) readyCountdownStartedAtMs = 0L
                    }
                } else if (!poseSyncReady) {
                    poseSyncFrames = 0
                }
            }

            if (session.state == AepState.READY && !game.snapshot().running) {
                val now = System.currentTimeMillis()
                if (!poseSyncReady) {
                    gameView.statusText = "Locking movement… stand naturally"
                } else {
                    if (readyCountdownStartedAtMs == 0L) readyCountdownStartedAtMs = now
                    val remaining = (readyCountdownMs - (now - readyCountdownStartedAtMs)).coerceAtLeast(0L)
                    if (remaining > 0L) {
                        val seconds = ((remaining + 499L) / 500L).coerceIn(1L, 3L)
                        gameView.statusText = "Tracking locked · starting in $seconds"
                    } else {
                        game.start()
                        session.startExperience()
                        gameView.statusText = "Go!"
                    }
                }
            }
            if (session.state == AepState.RUNNING) {
                val snapshot = game.tick(deltaMs)
                gameView.snapshot = snapshot
                if (snapshot.completed) session.completeExperience()
            }
            gameView.experienceState = session.state
            gameView.invalidate()
        }
        tvHost.start()
    }

    override fun onResume() {
        super.onResume()
        if (::tvHost.isInitialized && session.state == AepState.PAUSED) tvHost.resume()
    }

    override fun onPause() {
        if (::tvHost.isInitialized && session.state == AepState.RUNNING) tvHost.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::tvHost.isInitialized) tvHost.stop()
        liveDecodeExecutor.shutdownNow()
        gameView.joinQrBitmap?.let { if (!it.isRecycled) it.recycle() }
        gameView.playerBitmap?.let { if (!it.isRecycled) it.recycle() }
        super.onDestroy()
    }

    override fun onPlayerAction(action: PlayerAction) {
        runOnUiThread {
            when (action) {
                PlayerAction.DETECTED -> gameView.statusText = "Player ready"
                PlayerAction.LOST -> gameView.statusText = "Player lost — stand back in view"
                else -> game.onPlayerAction(action)
            }
        }
    }

    override fun onEvent(event: AepHostEvent) {
        // High-rate capabilities are deliberately conflated before they touch the UI thread.
        // Posting every camera.pose frame with runOnUiThread can create a seconds-long backlog
        // even though the stream itself is current.
        when (event) {
            is AepHostEvent.Pose -> {
                latestPoseFrame.set(event.frame)
                if (firstPoseLogged.compareAndSet(false, true)) logJoinTimings()
                return
            }
            is AepHostEvent.LivePerson -> {
                latestLiveFrame.set(event.frame)
                scheduleLatestLiveDecode()
                return
            }
            else -> Unit
        }

        // Everything that is not a high-rate frame, logged once.
        //
        // The connection axis alone could not explain the first hardware run: a session that was
        // COMPLETED when the phone went away came back as WAITING_FOR_PLAYER. Both readings of
        // that contradict a conformance scenario - if the same participant reconnected, 05 says
        // COMPLETED should have survived; if a new participant joined, 04 says the calibration
        // should have been discarded, and it was not. Only the participant axis says which
        // happened, and it was not being recorded.
        Log.i("AEP", "host: " + when (event) {
            // The catalogue is thirteen descriptors and would bury everything around it.
            is AepHostEvent.CapabilityCatalogChanged ->
                "CapabilityCatalogChanged(${event.participantId}, " +
                    "${event.descriptors.size} capabilities)"
            is AepHostEvent.GenericCapability -> "GenericCapability(${event.event.capability})"
            else -> event.toString()
        })

        runOnUiThread {
            when (event) {
                is AepHostEvent.JoinChanged -> {
                    // New session: never let a prior live-person bitmap leak into Private Pose.
                    gameView.playerBitmap?.let { if (!it.isRecycled) it.recycle() }
                    gameView.playerBitmap = null
                    gameView.lastLiveFrameAtMs = 0L
                    poseRigMapper.reset()
                    latestPoseFrame.set(null)
                    latestMotionState.set(null)
                    poseSyncFrames = 0
                    poseSyncReady = false
                    readyCountdownStartedAtMs = 0L
                    gameView.poseCharacterState = null
                    gameView.joinInfo = event.joinInfo
                    val previousQr = gameView.joinQrBitmap
                    gameView.joinQrBitmap = QrCodeBitmap.create(event.joinInfo.url)
                    if (previousQr != null && previousQr !== gameView.joinQrBitmap && !previousQr.isRecycled) {
                        previousQr.recycle()
                    }
                    gameView.statusText = "Scan the QR with Ambo Companion"
                }
                is AepHostEvent.ParticipantJoined -> {
                    poseRigMapper.reset()
                    latestPoseFrame.set(null)
                    latestMotionState.set(null)
                    poseSyncFrames = 0
                    poseSyncReady = false
                    readyCountdownStartedAtMs = 0L
                    gameView.poseCharacterState = null
                    gameView.statusText = "Connected · calibrating pose…"
                }
                is AepHostEvent.ParticipantReconnected -> {
                    gameView.statusText = "Reconnected · restoring experience…"
                }
                is AepHostEvent.ParticipantLeft -> {
                    gameView.statusText = "Companion disconnected"
                }
                is AepHostEvent.Error -> {
                    gameView.statusText = "${event.error.code}: ${event.error.message}"
                }
                else -> Unit
            }
            gameView.experienceState = session.state
            gameView.invalidate()
        }
    }

    private val firstPoseLogged = AtomicBoolean(false)

    /**
     * One line, once per session: where the time before first pose actually went.
     * Read with: adb logcat -s AEP:I
     *
     * The split matters because the three segments have different owners - platform,
     * the person holding the phone, and the device's camera/inference warm-up - and only
     * the first and last are ours to fix.
     */
    private fun logJoinTimings() {
        val t = session.joinTimeline.snapshot() ?: return
        Log.i(
            "AEP",
            "join timings: qrReady=${t.qrReadyMs}ms playerJoin=${t.playerJoinMs}ms " +
                "grant=${t.grantMs}ms firstPose=${t.firstPoseMs}ms total=${t.totalMs}ms"
        )
    }

    private fun scheduleLatestLiveDecode() {
        if (!liveDecodeRunning.compareAndSet(false, true)) return
        liveDecodeExecutor.execute {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val frame = latestLiveFrame.getAndSet(null) ?: break
                    // If a frame has already sat in the host for too long, skip it rather than showing stale motion.
                    val ageMs = System.currentTimeMillis() - frame.hostReceivedTimestampMs
                    if (ageMs > 650L) continue
                    val decoded = BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size) ?: continue
                    val cropped = cropTransparentPerson(decoded)
                    if (cropped !== decoded && !decoded.isRecycled) decoded.recycle()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            if (!cropped.isRecycled) cropped.recycle()
                        } else {
                            val previous = gameView.playerBitmap
                            gameView.playerBitmap = cropped
                            gameView.lastLiveFrameAtMs = System.currentTimeMillis()
                            if (previous != null && previous !== cropped && !previous.isRecycled) previous.recycle()
                            gameView.invalidate()
                        }
                    }
                }
            } finally {
                liveDecodeRunning.set(false)
                if (latestLiveFrame.get() != null) scheduleLatestLiveDecode()
            }
        }
    }

    /**
     * livevideo.person currently arrives as a transparent full-frame PNG. Cropping to the alpha bounds
     * makes the person large and ensures screen position comes from the low-latency pose stream rather
     * than from the person's camera-space position embedded inside the PNG.
     */
    private fun cropTransparentPerson(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 2 || h <= 2) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        val step = if (w * h > 900_000) 2 else 1
        var y = 0
        while (y < h) {
            var x = 0
            val row = y * w
            while (x < w) {
                if ((pixels[row + x] ushr 24) > 20) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX <= minX || maxY <= minY) return source
        val padX = ((maxX - minX) * .07f).toInt().coerceAtLeast(4)
        val padY = ((maxY - minY) * .04f).toInt().coerceAtLeast(4)
        val left = (minX - padX).coerceAtLeast(0)
        val top = (minY - padY).coerceAtLeast(0)
        val right = (maxX + padX).coerceAtMost(w - 1)
        val bottom = (maxY + padY).coerceAtMost(h - 1)
        return Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
    }


    private fun loadCanonicalRockDodgeConfig(): RockDodgeConfig {
        val text = assets.open("aep/rockdodge.v2.json").bufferedReader().use { it.readText() }
        val j = JSONObject(text)
        require(j.getInt("schemaVersion") == 2 && j.getString("experienceId") == "rock-dodge") {
            "Unsupported canonical Rock Dodge config"
        }
        return RockDodgeConfig(
            startingLives = j.getInt("startingLives"),
            difficultyIntervalMs = j.getLong("difficultyIntervalMs"),
            speedMultiplierPerLevel = j.getDouble("speedMultiplierPerLevel").toFloat(),
            spawnIntervalMultiplierPerLevel = j.getDouble("spawnIntervalMultiplierPerLevel").toFloat(),
            minimumSpawnDelayFloorMs = j.getLong("minimumSpawnDelayFloorMs"),
            minimumSpawnEveryMs = j.getLong("minimumSpawnEveryMs"),
            maximumSpawnEveryMs = j.getLong("maximumSpawnEveryMs"),
            minimumRockSpeed = j.getDouble("minimumRockSpeed").toFloat(),
            maximumRockSpeed = j.getDouble("maximumRockSpeed").toFloat(),
            playerMinX = j.getDouble("playerMinX").toFloat(),
            playerMaxX = j.getDouble("playerMaxX").toFloat(),
            playerY = j.getDouble("playerY").toFloat(),
            playerHalfWidth = j.getDouble("playerHalfWidth").toFloat(),
            playerHalfHeight = j.getDouble("playerHalfHeight").toFloat(),
            rockRadiusMin = j.getDouble("rockRadiusMin").toFloat(),
            rockRadiusMax = j.getDouble("rockRadiusMax").toFloat(),
            jumpDurationMs = j.getLong("jumpDurationMs"),
            crouchDurationMs = j.getLong("crouchDurationMs"),
            spawnXMin = j.getDouble("spawnXMin").toFloat(),
            spawnXMax = j.getDouble("spawnXMax").toFloat(),
            offscreenY = j.getDouble("offscreenY").toFloat(),
            jumpPlayerYOffset = j.getDouble("jumpPlayerYOffset").toFloat(),
            crouchHeightMultiplier = j.getDouble("crouchHeightMultiplier").toFloat(),
            crouchWidthMultiplier = j.getDouble("crouchWidthMultiplier").toFloat()
        )
    }

}
