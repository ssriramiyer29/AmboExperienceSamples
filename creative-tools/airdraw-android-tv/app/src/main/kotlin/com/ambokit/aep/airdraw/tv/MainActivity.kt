package com.ambokit.aep.airdraw.tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.AirDrawPoint
import com.ambokit.aep.airdraw.HandInput
import com.ambokit.aep.airdraw.HandInterpreter
import com.ambokit.aep.airdraw.StrokeSource
import com.ambokit.aep.airdraw.ToolStrip
import com.ambokit.aep.airdraw.ZoomGesture
import com.ambokit.aep.core.Aep
import com.ambokit.aep.core.AepCapabilityEvent
import com.ambokit.aep.core.AepConnectionState
import com.ambokit.aep.core.AepExperienceDefinition
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness
import com.ambokit.aep.host.AepPayloadJson
import com.ambokit.aep.host.ambokit.EmbeddedAndroidAmboKitHost
import com.ambokit.aep.host.tv.AndroidTvExperienceHost
import java.util.concurrent.atomic.AtomicReference

/**
 * AirDraw: the TV is the canvas and your hand in the air is the pen.
 *
 * One capability, `camera.hand`. Whichever hand pinches is the pen - there is nothing to set up
 * and nothing to choose. The palette is a strip down one edge that is always there, and a pinch
 * that begins inside it takes what it is over.
 *
 * Neither the drawing rules nor the gesture interpretation live here. This class owns the screen,
 * the session and the frame clock, and nothing else - ADR-0001 rule 2. Everything that decides
 * what the line looks like is in `airdraw-core`, which has tests and needs no TV.
 */
class MainActivity : Activity() {

    private lateinit var view: AirDrawView
    private lateinit var host: EmbeddedAndroidAmboKitHost
    private lateinit var session: com.ambokit.aep.core.AepSession
    private lateinit var tvHost: AndroidTvExperienceHost

    private val engine = AirDrawEngine()
    private val hands = HandInterpreter()
    private val zoom = ZoomGesture()
    private val strip = ToolStrip()

    /**
     * Hand frames are conflated before they reach the UI thread, exactly as RockDodge conflates
     * pose. Posting every frame with runOnUiThread builds a backlog seconds deep while the stream
     * itself is current, and a drawing tool that renders a backlog draws the past.
     */
    private val latestHandFrame = AtomicReference<AepHandFrame?>(null)

    private var drawing = false

    /** Whether a pen was present last frame, so a pinch is acted on once rather than every frame. */
    private var hadPen = false

    /** Which hand holds the pen, so a stroke does not silently transfer to the other one. */
    private var penSide: AepHandHandedness? = null

    private var frames = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = AirDrawView(this, engine, strip)
        setContentView(view)
        engine.colour = strip.colour

        host = EmbeddedAndroidAmboKitHost()
        session = Aep.start(
            AepExperienceDefinition(
                id = "com.ambokit.aep.airdraw",
                // What is declared here is what gets requested (ADR-0002 rule L16). Before AEP
                // 0.8.0 the Android host ignored this and asked for camera.pose regardless, which
                // is why AirDraw needs that release as a floor.
                capabilities = listOf("camera.hand"),
                // AirDraw wants raw positions, not a calibrated body model. Nobody should have to
                // stand still with their arms out before they can draw.
                requiresCalibration = false
            ),
            host
        ).also { live ->
            live.capabilities.event += { event -> onCapabilityEvent(event) }
            live.connectionChanged += { state ->
                runOnUiThread {
                    view.connection = state
                    // A phone that has gone away is not still pinching. Without this the stroke
                    // in progress stays open and joins up with whatever is drawn next.
                    if (state != AepConnectionState.CONNECTED) letGo()
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
            live.joinChanged += { join ->
                runOnUiThread {
                    view.joinUrl = join.url
                    view.joinQr = QrCodeBitmap.create(join.url)
                    view.invalidate()
                }
            }
            live.error += { error ->
                runOnUiThread { view.status = "${error.code}: ${error.message}"; view.invalidate() }
            }
        }

        tvHost = AndroidTvExperienceHost(session) { _ -> onFrame() }
        tvHost.start()
    }

    private fun letGo() {
        if (drawing) engine.end()
        drawing = false
        hadPen = false
        penSide = null
        hands.reset()
        zoom.end()
    }

    /**
     * One display frame: consume the freshest input, then draw.
     *
     * The curve is evaluated here rather than when samples arrive, which is what turns fifteen
     * hand samples a second into a continuous line.
     */
    private fun onFrame() {
        view.sizeViewport()
        consumeHands()
        view.invalidate()
    }

    private fun consumeHands() {
        val frame = latestHandFrame.getAndSet(null) ?: return
        val aspect = engine.viewport.aspect
        val input = hands.read(frame, aspect)
        view.tracking = input.tracked
        view.cursors = cursorsFor(input)
        logInput(input)

        val left = input.left
        val right = input.right
        val bothPinching = left != null && right != null &&
            hands.isPinching(AepHandHandedness.LEFT) && hands.isPinching(AepHandHandedness.RIGHT)

        // Two pinching hands is a zoom, and never a mark. Checked before anything else, so
        // bringing the second hand in does not leave a stray line behind it.
        if (bothPinching) {
            if (drawing) { engine.cancel(); drawing = false }
            hadPen = false
            penSide = null
            zoom.update(left, right, true)?.let {
                engine.viewport.zoomAbout(it.focusX, it.focusY, it.scale)
                engine.viewport.panBy(it.panX, it.panY)
            }
            return
        }
        zoom.end()

        val pen = hands.penHand(input)
        if (pen == null) {
            if (drawing) engine.end()
            drawing = false
            hadPen = false
            penSide = null
            return
        }

        // The pen changing hands mid-stroke would otherwise join two separate marks with a line
        // straight across the picture.
        if (drawing && penSide != null && pen.handedness != penSide) {
            engine.end()
            drawing = false
            hadPen = false
        }

        if (!hadPen) {
            hadPen = true
            penSide = pen.handedness
            if (strip.contains(pen.x)) {
                // A pinch that begins on the strip takes what it is over, and draws nothing.
                val taken = strip.select(strip.cellAt(pen.x, pen.y, aspect))
                if (taken?.kind == ToolStrip.Kind.UNDO) engine.undo()
                engine.colour = strip.colour
            } else {
                engine.begin(StrokeSource.AIR, pointFor(pen.x, pen.y))
                drawing = true
            }
            return
        }

        // A stroke that began on the canvas keeps drawing wherever the hand goes, including over
        // the strip. The strip can take a pinch; it can never take a mark already in progress.
        if (drawing) engine.extend(pointFor(pen.x, pen.y))
    }

    private fun pointFor(x: Float, y: Float) = AirDrawPoint(
        x = engine.viewport.toDocumentX(x * view.width),
        y = engine.viewport.toDocumentY(y * view.width),
        pressure = 1f,
        timeMs = System.currentTimeMillis()
    )

    /** Every visible hand gets a cursor, so "can it see me?" is answerable at a glance. */
    private fun cursorsFor(input: HandInput): List<AirDrawView.Cursor> = buildList {
        input.left?.let { add(AirDrawView.Cursor(it.x, it.y, hands.isPinching(AepHandHandedness.LEFT))) }
        input.right?.let { add(AirDrawView.Cursor(it.x, it.y, hands.isPinching(AepHandHandedness.RIGHT))) }
        input.unplaced?.let { add(AirDrawView.Cursor(it.x, it.y, hands.isLonePinching)) }
    }

    /**
     * Roughly once a second, what the hands are actually doing.
     *
     * The pinch thresholds were set before anyone had pinched at a television, and on hardware
     * they missed pinches that had certainly been made. Tuning them from a second guess would be
     * no better than the first, so this prints the numbers instead:
     *
     *     adb logcat -s AirDraw
     */
    private fun logInput(input: HandInput) {
        if (++frames % 15L != 0L) return
        Log.d(
            "AirDraw",
            "tracked=${input.tracked} hands=${input.handCount}" +
                " L=${format(hands.lastLeftPinch)} R=${format(hands.lastRightPinch)}" +
                " pinchL=${hands.isPinching(AepHandHandedness.LEFT)}" +
                " pinchR=${hands.isPinching(AepHandHandedness.RIGHT)}" +
                " drawing=$drawing zoom=${zoom.isActive} scale=${"%.2f".format(engine.viewport.scale)}" +
                " strokes=${engine.drawing.all.size}"
        )
    }

    private fun format(value: Float?) = if (value == null) "--" else "%.2f".format(value)

    /**
     * Hand frames arrive through the generic path, because AEP wraps only pose and live person
     * ergonomically. That is not a limitation here: the generated parser turns a payload into a
     * typed model or returns null, so a `ready` event - which carries no frame - simply fails to
     * parse and is ignored without anyone having to test for it.
     */
    private fun onCapabilityEvent(event: AepCapabilityEvent) {
        if (event.capability != "camera.hand") return
        val payload = AepPayloadJson.decode(event.payloadJson) ?: return
        AepHandFrame.fromMap(payload)?.let { latestHandFrame.set(it) }
    }

    override fun onResume() {
        super.onResume()
        if (::tvHost.isInitialized) tvHost.resume()
    }

    override fun onPause() {
        if (::tvHost.isInitialized) tvHost.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::tvHost.isInitialized) tvHost.stop()
        view.recycleBitmaps()
        super.onDestroy()
    }
}
