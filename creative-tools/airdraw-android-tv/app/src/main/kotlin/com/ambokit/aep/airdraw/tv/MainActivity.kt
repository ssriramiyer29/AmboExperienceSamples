package com.ambokit.aep.airdraw.tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.AirDrawPoint
import com.ambokit.aep.airdraw.Autosave
import com.ambokit.aep.airdraw.CalibrationPhase
import com.ambokit.aep.airdraw.CanvasMode
import com.ambokit.aep.airdraw.ControlScheme
import com.ambokit.aep.airdraw.ControlState
import com.ambokit.aep.airdraw.DrawingRecord
import com.ambokit.aep.airdraw.HandShape
import com.ambokit.aep.airdraw.JitterMeter
import com.ambokit.aep.airdraw.StrokeSmoothing
import com.ambokit.aep.airdraw.PointerMapper
import com.ambokit.aep.airdraw.PointerOrigin
import com.ambokit.aep.airdraw.ReachCalibrator
import com.ambokit.aep.airdraw.StrokeSource
import com.ambokit.aep.airdraw.ToolStrip
import com.ambokit.aep.core.Aep
import com.ambokit.aep.core.AepCapabilityEvent
import com.ambokit.aep.core.AepCapabilityId
import com.ambokit.aep.core.AepConnectionState
import com.ambokit.aep.core.AepExperienceDefinition
import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.host.AepPayloadJson
import com.ambokit.aep.host.ambokit.EmbeddedAndroidAmboKitHost
import com.ambokit.aep.host.tv.AndroidTvExperienceHost
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/**
 * AirDraw: the TV is the canvas and your arm in the air is the pen.
 *
 * **Two capabilities, each doing what it is good at.** `camera.hand` reports what shape a hand is
 * in - pinched, open, closed - and `camera.pose` reports where the wrist is. That split is the
 * point: the hand provider loses the hand on a large fraction of frames (13% in one logged
 * session, 43% in another) and each loss used to break the stroke being drawn, but a *shape* does
 * not care about a dropped frame the way a *position* does. So the discrete signals come from the
 * hand and the continuous one from the body, whose wrist keeps tracking when the hand-detection
 * crop fails.
 *
 * **What happens if that costs too much.** Running both models on the phone at once is the one
 * assumption here with no measurement behind it. If it throttles, `PointerSource` falls back to
 * the hand's own wrist landmark and the whole control scheme carries on working with less
 * continuity - a bad day rather than a rewrite.
 *
 * **The controls.** Pinch is a master latch: it turns drawing on and leaves it on. Underneath it,
 * an open palm marks, a fist drags the canvas, and two fists scale it. A pinch inside the palette
 * strip takes whatever it is over.
 *
 * Neither the drawing rules nor the gesture interpretation live here. This class owns the screen,
 * the session, the frame clock and the filesystem, and nothing else - ADR-0001 rule 2.
 */
class MainActivity : Activity() {

    private lateinit var view: AirDrawView
    private lateinit var overlay: OverlayView
    private lateinit var host: EmbeddedAndroidAmboKitHost
    private lateinit var session: com.ambokit.aep.core.AepSession
    private lateinit var tvHost: AndroidTvExperienceHost
    private lateinit var files: DrawingFiles

    private val engine = AirDrawEngine()
    private val control = ControlScheme()
    private val calibrator = ReachCalibrator()

    /**
     * Camera coordinates to canvas coordinates: mirror, then map through this person's reach.
     *
     * Held here rather than inside the hand interpreter, because the pointer now arrives from the
     * body as often as from the hand and both need the same treatment.
     */
    private val pointerMapper = PointerMapper()

    /**
     * How much the pointer wanders, measured rather than assumed.
     *
     * The rework changed where the pointer comes from - the pose wrist rather than the hand's
     * pinch midpoint - and those are different signals with different noise. A filter chosen for
     * one is not evidence about the other, so this measures the new signal and lets
     * [StrokeSmoothing.forJitter] pick from what it finds.
     */
    private val jitter = JitterMeter()
    private val strip = ToolStrip()
    private val autosave = Autosave()

    /**
     * Frames are conflated before they reach the UI thread, exactly as RockDodge conflates pose.
     * Posting every frame with runOnUiThread builds a backlog seconds deep while the stream itself
     * is current, and a drawing tool that renders a backlog draws the past.
     */
    private val latestHandFrame = AtomicReference<AepHandFrame?>(null)
    private val latestPoseFrame = AtomicReference<AepPoseFrame?>(null)

    private var drawing = false

    /** Which drawing is open. Null before Start Drawing has been pressed. */
    private var currentId: String? = null
    private var createdMs = 0L
    private var drawingName: String? = null

    /** True once the person has pressed Start Drawing and the overlay has gone. */
    private var started = false

    /** Where the pen was last seen, so a stroke is not bridged across a jump. */
    private var lastPenX: Float? = null
    private var lastPenY: Float? = null
    private var penWasMissing = false

    // Counted over the interval rather than sampled at the end of it. The previous version
    // printed the state of one frame in fifteen, which cannot measure how often the tracker
    // loses the hand or for how long - and those turned out to be the numbers that mattered.
    private var frames = 0L
    private var framesNoPointer = 0L
    private var framesPoseDriven = 0L
    private var strokesStarted = 0
    private var strokesEnded = 0

    /** QR encoding, kept off the thread that has to stay responsive. */
    private val qrWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "airdraw-qr").apply { isDaemon = true }
    }

    /** Saving, for the same reason. A file write on the frame thread is a visible stutter. */
    private val saveWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "airdraw-save").apply { isDaemon = true }
    }

    private var lastJoinUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        files = DrawingFiles(File(filesDir, "drawings"))

        view = AirDrawView(this, engine, strip)
        overlay = OverlayView(this).apply {
            onStart = { id -> startDrawing(id) }
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Hidden until the phone has joined. Until then the canvas view shows the QR code, which
        // is the only thing there is to do.
        overlay.visibility = android.view.View.GONE
        applyPen()

        host = EmbeddedAndroidAmboKitHost()
        session = Aep.start(
            AepExperienceDefinition(
                id = "com.ambokit.aep.airdraw",
                // What is declared here is what gets requested (ADR-0002 rule L16). Before AEP
                // 0.8.0 the Android host ignored this and asked for camera.pose regardless, which
                // is why AirDraw needs that release as a floor.
                capabilities = listOf("camera.hand", "camera.pose"),
                // False, even though AirDraw now calibrates - because AEP's `requiresCalibration`
                // does not mean what AirDraw needs.
                //
                // Setting it true puts the session in CALIBRATING until `PoseCalibrator` returns
                // a profile, and that calibrator requires both ankles (`leftAnkle ?: return
                // null`). AirDraw is an arm activity whose player may be sitting on a sofa or
                // standing close to the camera, so their feet are routinely out of shot - and
                // the session would sit in CALIBRATING for the whole session, for a body model
                // AirDraw never reads.
                //
                // What AirDraw calibrates is reach: how far these arms actually go, measured from
                // shoulders and hips, so the canvas fits them from the first stroke. That is
                // `ReachCalibrator`, it gates the Start Drawing button on the overlay, and it is
                // a different question from the one this flag asks.
                requiresCalibration = false
            ),
            host
        ).also { live ->
            live.capabilities.event += { event -> onCapabilityEvent(event) }
            // Pose has an ergonomic path; hands do not. `AepPoseFrame` has no `fromMap`, because
            // it is hand-written in Contracts.kt rather than generated from a schema like the
            // hand models - so pose arrives typed and hands arrive as payloads to parse.
            live.player.poseChanged += { frame ->
                latestPoseFrame.set(frame)
                // The overlay appears once a person is actually in shot. Showing it the instant
                // the phone joins would start a calibration countdown against an empty room.
                if (!started) runOnUiThread { showOverlay() }
            }
            live.connectionChanged += { state ->
                runOnUiThread {
                    view.connection = state
                    // A phone that has gone away is not still pinching. Without this the stroke
                    // in progress stays open and joins up with whatever is drawn next.
                    if (state != AepConnectionState.CONNECTED) {
                        letGo()
                        view.streaming = false
                    }
                    view.status = when (state) {
                        AepConnectionState.CONNECTING -> "Starting…"
                        AepConnectionState.CONNECTED -> "Scan the code with Ambo Companion"
                        AepConnectionState.RECONNECTING -> "Reconnecting to your phone…"
                        AepConnectionState.REJOIN_REQUIRED -> "Connection lost — scan again"
                        AepConnectionState.CLOSED -> "Disconnected"
                    }
                    view.invalidate()
                }
            }
            live.joinChanged += { join -> onJoinChanged(join.url) }
            live.error += { error ->
                runOnUiThread { view.status = "${error.code}: ${error.message}"; view.invalidate() }
            }
        }

        tvHost = AndroidTvExperienceHost(session) { _ -> onFrame() }
        tvHost.start()
    }

    /**
     * Put the join URL on screen, and the QR alongside it once it exists.
     *
     * Encoding happens on a worker, because doing it on the main thread is what was killing the
     * app at startup - see [QrCodeBitmap]. The URL appears immediately either way, so a slow
     * encode degrades to "type this in" rather than to a blank screen.
     */
    private fun onJoinChanged(url: String) {
        runOnUiThread {
            view.joinUrl = url
            view.invalidate()
        }
        if (url == lastJoinUrl) return
        lastJoinUrl = url
        qrWorker.execute {
            val bitmap = QrCodeBitmap.create(url)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                // The join can be reissued, and each reissue used to leave the last megabyte of
                // bitmap behind it.
                view.joinQr?.let { if (!it.isRecycled) it.recycle() }
                view.joinQr = bitmap
                view.invalidate()
            }
        }
    }

    // ---- the overlay ------------------------------------------------------------------------

    /**
     * Show the overlay once the phone has joined, and keep its gallery current.
     *
     * Listing drawings reads the filesystem, so it happens here - once, on the transition - rather
     * than every frame while the overlay is up.
     */
    private fun showOverlay() {
        if (started || overlay.visibility == android.view.View.VISIBLE) return
        val listing = files.list()
        overlay.showGallery(listing, files)
        overlay.visibility = android.view.View.VISIBLE
        overlay.requestFocus()
    }

    /**
     * Leave the overlay and open a canvas: either the chosen drawing or a blank one.
     *
     * The measured reach goes into the envelope here rather than when calibration completed,
     * because until this moment the person may still have been moving into position and the
     * calibrator is still widening the rectangle from what it sees.
     */
    private fun startDrawing(id: String?) {
        val record = id?.let { files.load(it) }
        engine.cancel()
        engine.drawing.load(record?.strokes.orEmpty())

        if (record != null) {
            currentId = record.id
            createdMs = record.createdMs
            drawingName = record.name
        } else {
            currentId = files.newId()
            createdMs = System.currentTimeMillis()
            drawingName = null
        }

        // Seeded now rather than when calibration completed: until this moment the person may
        // still have been moving into position, and the calibrator goes on widening the
        // rectangle from everything it sees.
        pointerMapper.seedFrom(calibrator.profile)
        // The hand they raised is the hand that draws. Nothing has to be inferred from here on.
        control.drawingHand = calibrator.drawingHand
        jitter.reset()
        autosave.reset()
        started = true
        overlay.visibility = android.view.View.GONE
        view.requestFocus()
        view.invalidate()
    }

    // ---- frames -------------------------------------------------------------------------------

    /** One place where a stroke ends, so the diagnostics can count every one of them. */
    private fun endStroke() {
        if (!drawing) return
        if (engine.end()) {
            strokesEnded++
            autosave.changed(System.currentTimeMillis())
        }
        drawing = false
    }

    private fun letGo() {
        endStroke()
        drawing = false
        lastPenX = null
        lastPenY = null
        panFrom = null
        pinchHandled = false
        control.reset()
    }

    /**
     * One display frame: consume the freshest input, then draw.
     *
     * The curve is evaluated here rather than when samples arrive, which is what turns fifteen
     * samples a second into a continuous line.
     */
    private fun onFrame() {
        view.sizeViewport()
        val now = System.currentTimeMillis()

        latestPoseFrame.getAndSet(null)?.let { pose ->
            control.observePose(pose, now)
            if (!started) {
                calibrator.observeRaisedHand(pose)
                calibrator.observeSweep(pose)
                calibrator.accept(pose)
                overlay.setCalibration(calibrator.progress, calibrator.phase, seenPerson = true)
            }
        }

        if (started) consumeHands(now) else latestHandFrame.set(null)
        maybeSave(now)
        view.invalidate()
    }

    private fun consumeHands(now: Long) {
        val frame = latestHandFrame.getAndSet(null)
        // The screen's shape, not the sheet's.
        //
        // The pointer lives in the whole screen's space, toolbar included. Mapping it through the
        // viewport's aspect - which describes only the paper - capped its y above where the
        // toolbar starts, so the band could never be reached however far anybody stretched.
        val aspect = view.screenAspect
        val state = control.update(frame, now)
        view.tracking = state.pointer != null
        try {
            route(state, aspect, now)
        } finally {
            // Logged after the decisions, not before. Logging first printed the previous frame's
            // verdict beside this frame's readings, which is a confusing thing to hand someone
            // who is trying to work out why a stroke ended.
            logFrame(state)
        }
    }

    /**
     * What this frame's control state does to the canvas.
     *
     * The shape of this is the control scheme, and it reads in the order the modes take
     * priority: pan, then drawing. Anything else leaves the canvas alone.
     */
    private fun route(state: ControlState, aspect: Float, now: Long) {
        val pointer = state.pointer
        val pen = pointerMapper.map(pointer, aspect, learning = !drawing)

        if (pen == null) {
            // The pointer blinked. Hold the stroke open and add nothing to it - the alternative
            // is ending a line the player is still drawing, several times a minute.
            penWasMissing = true
            // A gap is not movement: measuring the distance either side of it as one step would
            // read a dropout as an extremely noisy sensor and switch on the heaviest filter.
            jitter.interrupted()
            view.cursors = cursorsFor(null, state)
            return
        }

        // The pointer came back somewhere else entirely. Bridging that would draw a straight line
        // across the picture, which is worse than the seam it was meant to avoid. Only on the
        // frame it returns: applied every frame, this chopped fast strokes.
        if (penWasMissing && drawing) {
            val fromX = lastPenX
            val fromY = lastPenY
            if (fromX != null && fromY != null && hypot(pen.first - fromX, pen.second - fromY) > REACQUIRE_JUMP) {
                endStroke()
            }
        }
        penWasMissing = false
        lastPenX = pen.first
        lastPenY = pen.second
        view.cursors = cursorsFor(pen, state)

        // Applied between strokes only. Rebuilding the filter mid-stroke resets its history, and
        // a line that changes its smoothing halfway along has a visible seam in it.
        jitter.observe(pen.first, pen.second)?.let { pixels ->
            if (!drawing) {
                val next = StrokeSmoothing.forJitter(pixels)
                if (next != engine.smoothing) {
                    engine.smoothing = next
                    Log.d("AirDraw", "jitter measured at ${"%.1f".format(pixels)}px -> smoothing $next")
                }
            }
        }

        if (state.mode == CanvasMode.PANNING) {
            if (drawing) endStroke()
            panBy(pen, now)
            return
        }
        panFrom = null

        // A pinch inside the strip takes what it is over, and draws nothing. Handled on the frame
        // the pinch lands rather than while it is held, so one pinch is one selection.
        if (state.shape == HandShape.PINCH && strip.contains(pen.second, aspect)) {
            if (pinchHandled) return
            pinchHandled = true
            if (drawing) endStroke()
            actOn(strip.select(strip.cellAt(pen.first, pen.second, aspect)), now)
            return
        }
        if (state.shape != HandShape.PINCH) pinchHandled = false

        if (state.mode == CanvasMode.DRAWING) {
            if (!drawing) {
                // A stroke never begins inside the strip; the strip belongs to pinches.
                if (strip.contains(pen.second, aspect)) return
                val point = pointFor(pen.first, pen.second) ?: return
                engine.begin(StrokeSource.AIR, point)
                drawing = true
                strokesStarted++
            } else {
                // A stroke that began on the canvas keeps drawing wherever the pointer goes,
                // including over the strip.
                pointFor(pen.first, pen.second)?.let { engine.extend(it) }
            }
            return
        }

        endStroke()
    }

    /** Whether the current pinch has already been acted on, so holding one selects once. */
    private var pinchHandled = false

    /** Where the pan gesture last had the pointer, in pointer units. */
    private var panFrom: Pair<Float, Float>? = null

    private fun panBy(pen: Pair<Float, Float>, now: Long) {
        val from = panFrom
        if (from != null) {
            // Document units, so a pan moves the drawing by as much as the hand moved across it
            // whatever the zoom - the same conversion the viewport uses for everything else.
            val dx = (pen.first - from.first) * view.width / (engine.viewport.scale * view.width)
            val dy = (pen.second - from.second) * view.width / (engine.viewport.scale * view.width)
            engine.viewport.panBy(dx, dy)
        }
        panFrom = pen
    }

    /** Where on the paper a pointer is. Null when it is over the chrome rather than the sheet. */
    private fun pointFor(x: Float, y: Float): AirDrawPoint? =
        view.documentPoint(x, y, System.currentTimeMillis())

    /** Push the palette's selection onto the engine, so the pen is what the strip says it is. */
    private fun applyPen() {
        engine.colour = strip.colour
        engine.baseWidth = strip.strokeWidth
        engine.brush = strip.brush
        engine.erasing = strip.erasing
    }

    /**
     * Every visible pointer gets a cursor, so "can it see me?" is answerable at a glance.
     *
     * When the pen is latched on but no pointer can be seen, the cursor stays where it last was
     * rather than vanishing. The pen has not been lifted - the tracker has blinked - and a cursor
     * that disappears mid-stroke reads as the app giving up, which was reported exactly that way.
     */
    private fun cursorsFor(pen: Pair<Float, Float>?, state: ControlState): List<AirDrawView.Cursor> =
        buildList {
            if (pen != null) {
                add(AirDrawView.Cursor(pen.first, pen.second, state.shape == HandShape.PINCH))
            } else if (state.drawOn) {
                val x = lastPenX
                val y = lastPenY
                if (x != null && y != null) {
                    add(AirDrawView.Cursor(x, y, pinching = true, waiting = true))
                }
            }
        }

    // ---- saving --------------------------------------------------------------------------------

    private fun record(now: Long): DrawingRecord? {
        val id = currentId ?: return null
        return DrawingRecord(id, createdMs, now, drawingName, engine.drawing.all.toList())
    }

    /**
     * Write the drawing if one is due.
     *
     * The work is only marked clean once the write has actually succeeded, which is why
     * [Autosave.saved] is called from the worker and not here: clearing the debt when a write is
     * *decided* would lose everything a failed write was carrying, and nobody would find out
     * until the drawing came back short.
     */
    private fun maybeSave(now: Long) {
        if (!started || !autosave.isDue(now)) return
        val snapshot = record(now) ?: return
        saveWorker.execute { if (files.save(snapshot)) autosave.saved() }
    }

    /** The palette's Save: keep a copy as it is now, and carry on drawing in the original. */
    private fun snapshot(now: Long) {
        val current = record(now) ?: return
        val copy = current.snapshot(files.newId(), now)
        saveWorker.execute { files.save(copy) }
    }

    // ---- diagnostics ---------------------------------------------------------------------------

    /**
     * Roughly every few seconds, what the hands and the pointer are actually doing.
     *
     * The interesting number here is `pose=`: the share of frames the pointer came from the body
     * rather than falling back to the hand's own wrist. If that is not close to 100% on a healthy
     * session, hand and pose inference are not coexisting on the phone - which is the one
     * assumption in this design with no measurement behind it.
     *
     *     adb logcat -s AirDraw
     */
    private fun logFrame(state: ControlState) {
        frames++
        if (state.pointer == null) framesNoPointer++
        if (state.pointer?.origin == PointerOrigin.POSE_WRIST) framesPoseDriven++
        if (frames % INTERVAL != 0L) return
        Log.d(
            "AirDraw",
            "over $INTERVAL frames: noPointer=${percent(framesNoPointer)}" +
                " pose=${percent(framesPoseDriven)}" +
                " handovers=${control.pointerSource.handovers}" +
                " strokes=+$strokesStarted/-$strokesEnded" +
                " | mode=${state.mode} shape=${state.shape} drawOn=${state.drawOn}" +
                " ext=${control.lastExtended ?: "-"} gap=${control.lastGap?.let { "%.2f".format(it) } ?: "--"}" +
                " hand=${control.drawingHand?.name ?: "any"}" +
                " age=${state.pointer?.ageMs ?: -1}ms" +
                " pen=[colour=${strip.colourIndex} w=${strip.sizeIndex}" +
                " brush=${strip.brush} erasing=${strip.erasing}]" +
                " reach=[${pointerMapper.reach}]" +
                " jitter=${jitter.pixels?.let { "%.0fpx".format(it) } ?: "--"}" +
                " smoothing=${if (engine.smoothing.enabled) "on" else "off"}" +
                " zoom=${"%.2f".format(engine.viewport.scale)}" +
                " strokesOnCanvas=${engine.drawing.all.size} unsaved=${autosave.pending}"
        )
        framesNoPointer = 0
        framesPoseDriven = 0
        strokesStarted = 0
        strokesEnded = 0
    }

    private fun percent(count: Long) = "%.0f%%".format(100.0 * count / INTERVAL)

    private companion object {
        /**
         * How far the pen may move while the tracker is blinking and still be the same stroke.
         *
         * In pointer units, so a tenth of the screen's width. Far enough to cover a hand that
         * kept moving through a dropout, short enough that a hand reacquired somewhere else
         * starts a new mark instead of ruling a line across the drawing.
         */
        const val REACQUIRE_JUMP = 0.10f

        /** Frames per diagnostic line: about five seconds at the rate frames arrive. */
        const val INTERVAL = 60L
    }

    /**
     * Frames arrive through the generic path, because AEP wraps only pose and live person
     * ergonomically. That is not a limitation here: the generated parser turns a payload into a
     * typed model or returns null, so a `ready` event - which carries no frame - simply fails to
     * parse and is ignored without anyone having to test for it.
     */
    private fun onCapabilityEvent(event: AepCapabilityEvent) {
        // By capability id rather than by string. `camera.hand` and `camera.hand@1` are both
        // legal spellings of the same capability and AEP 0.9.0 exists largely because four
        // places disagreed about that; comparing with == here would reintroduce it.
        if (!AepCapabilityId.sameCapability(event.capability, "camera.hand")) return
        if (!view.streaming) runOnUiThread { view.streaming = true }
        val payload = AepPayloadJson.decode(event.payloadJson) ?: return
        AepHandFrame.fromMap(payload)?.let { latestHandFrame.set(it) }
    }

    /**
     * The remote, as a second way onto the toolbar.
     *
     * Not a fallback for the hand - both work at once - but reaching the bottom of the screen is
     * the furthest a drawing arm has to go, and on a device it was the last thing to become
     * reliable. Picking a colour should not depend on that.
     *
     * **`dispatchKeyEvent`, not `onKeyDown`.** This runs before the view hierarchy sees the key,
     * where `onKeyDown` runs after. The first attempt used `onKeyDown` and nothing happened on a
     * television - the canvas view is focusable, so a D-pad press had somewhere else to go first.
     * Rather than work out exactly where it went, this takes the key before anything can. Every
     * press is logged, so if it still does not arrive the log says so rather than leaving it to
     * be guessed at again.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("AirDraw", "key ${event.keyCode} started=$started toolbarFocus=${strip.focusIndex}")
            if (started && handleRemote(event.keyCode)) {
                view.invalidate()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleRemote(keyCode: Int): Boolean {
        val aspect = view.screenAspect
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (strip.hasFocus) false else { strip.focusEnter(aspect); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK ->
                if (strip.hasFocus) { strip.focusClear(); true } else false
            KeyEvent.KEYCODE_DPAD_LEFT -> strip.focusMove(-1, aspect)
            KeyEvent.KEYCODE_DPAD_RIGHT -> strip.focusMove(1, aspect)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (!strip.hasFocus) false else {
                    // A stroke in progress ends first: a colour chosen mid-line would otherwise
                    // recolour the line being drawn when it was committed.
                    if (drawing) endStroke()
                    actOn(strip.focusSelect(aspect), System.currentTimeMillis())
                    true
                }
            else -> false
        }
    }

    /** What a taken cell does, whether a hand pinched it or the remote pressed it. */
    private fun actOn(taken: ToolStrip.Cell?, now: Long) {
        when (taken?.kind) {
            ToolStrip.Kind.UNDO -> if (engine.undo()) autosave.changed(now)
            // Clear is one step of undo, not a wipe - a child who waves at the wrong moment has
            // not lost the picture.
            ToolStrip.Kind.CLEAR -> if (engine.clear()) autosave.changed(now)
            ToolStrip.Kind.SAVE -> snapshot(now)
            else -> Unit
        }
        applyPen()
    }

    override fun onResume() {
        super.onResume()
        if (::tvHost.isInitialized) tvHost.resume()
    }

    override fun onPause() {
        // Anything unsaved goes to disk now. onPause is the last callback guaranteed before a
        // television decides to reclaim the app, and a drawing lost to that would be lost
        // silently.
        record(System.currentTimeMillis())?.let { pending ->
            if (autosave.isDirty) saveWorker.execute { if (files.save(pending)) autosave.saved() }
        }
        if (::tvHost.isInitialized) tvHost.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::tvHost.isInitialized) tvHost.stop()
        qrWorker.shutdownNow()
        saveWorker.shutdown()
        view.recycleBitmaps()
        super.onDestroy()
    }
}
