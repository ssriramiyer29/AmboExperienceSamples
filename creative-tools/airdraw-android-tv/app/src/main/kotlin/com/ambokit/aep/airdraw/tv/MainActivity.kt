package com.ambokit.aep.airdraw.tv

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.AirDrawPoint
import com.ambokit.aep.airdraw.HandInterpreter
import com.ambokit.aep.airdraw.StrokeSource
import com.ambokit.aep.airdraw.ZoomGesture
import com.ambokit.aep.core.Aep
import com.ambokit.aep.core.AepCapabilityEvent
import com.ambokit.aep.core.AepConnectionState
import com.ambokit.aep.core.AepExperienceDefinition
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepTouch
import com.ambokit.aep.core.capabilities.AepTouchPhase
import com.ambokit.aep.host.AepPayloadJson
import com.ambokit.aep.host.ambokit.EmbeddedAndroidAmboKitHost
import com.ambokit.aep.host.tv.AndroidTvExperienceHost
import java.util.concurrent.atomic.AtomicReference

/**
 * AirDraw: the TV is the canvas, the phone is the instrument.
 *
 * Two capabilities, composed. `camera.hand` gives whole-arm strokes with pinch to draw and two
 * hands to zoom; `input.touch` turns the phone into a pressure-sensitive tablet for detail. The
 * same canvas receives both, and each stroke remembers which made it, because pressure and speed
 * earn their width differently.
 *
 * Neither the drawing rules nor the gesture interpretation live here. This class owns the screen,
 * the session and the frame clock, and nothing else — ADR-0001 rule 2. Everything that decides
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

    /**
     * Capability payloads are conflated before they reach the UI thread, exactly as RockDodge
     * conflates pose. Posting every frame with runOnUiThread builds a backlog seconds deep while
     * the stream itself is current, and a drawing tool that renders a backlog draws the past.
     */
    private val latestHandFrame = AtomicReference<AepHandFrame?>(null)
    private val pendingTouches = ArrayList<AepTouch>()

    private var drawingWithAir = false
    private var drawingWithTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = AirDrawView(this, engine)
        setContentView(view)

        host = EmbeddedAndroidAmboKitHost()
        session = Aep.start(
            AepExperienceDefinition(
                id = "com.ambokit.aep.airdraw",
                // What is declared here is what gets requested (ADR-0002 rule L16). Before AEP
                // 0.8.0 the Android host ignored this and asked for camera.pose regardless, which
                // is why AirDraw needs that release as a floor.
                capabilities = listOf("camera.hand", "input.touch"),
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

    /**
     * One display frame: consume the freshest input, then draw.
     *
     * The curve is evaluated here rather than when samples arrive, which is what turns fifteen
     * hand samples a second into a continuous line.
     */
    private fun onFrame() {
        view.sizeViewport()
        consumeTouches()
        consumeHands()
        view.invalidate()
    }

    private fun consumeHands() {
        val frame = latestHandFrame.getAndSet(null) ?: return
        val input = hands.read(frame, engine.viewport.aspect)
        view.tracking = input.tracked
        view.cursor = input.drawing?.let { it.x to it.y }

        // Two pinching hands means zoom, not draw. Checked first, so starting a zoom never leaves
        // a stray mark where the second hand came in.
        val bothPinching = input.handCount == 2 &&
            (input.other?.pinch ?: 0f) >= 0.6f && hands.isPinching
        val change = zoom.update(input.drawing, input.other, bothPinching)
        if (change != null) {
            if (drawingWithAir) { engine.cancel(); drawingWithAir = false }
            engine.viewport.zoomAbout(change.focusX, change.focusY, change.scale)
            engine.viewport.panBy(change.panX, change.panY)
            return
        }
        if (bothPinching) {
            if (drawingWithAir) { engine.cancel(); drawingWithAir = false }
            return
        }

        val pointer = input.drawing
        if (pointer == null) {
            if (drawingWithAir) { engine.end(); drawingWithAir = false }
            return
        }

        val point = AirDrawPoint(
            x = engine.viewport.toDocumentX(pointer.x * view.width),
            y = engine.viewport.toDocumentY(pointer.y * view.width),
            pressure = 1f,
            timeMs = System.currentTimeMillis()
        )
        when {
            hands.isPinching && !drawingWithAir -> { engine.begin(StrokeSource.AIR, point); drawingWithAir = true }
            hands.isPinching -> engine.extend(point)
            drawingWithAir -> { engine.end(); drawingWithAir = false }
        }
    }

    private fun consumeTouches() {
        val touches: List<AepTouch>
        synchronized(pendingTouches) {
            if (pendingTouches.isEmpty()) return
            touches = ArrayList(pendingTouches)
            pendingTouches.clear()
        }
        for (touch in touches) {
            // One finger draws. A second is the phone's own pinch-to-zoom and is handled by the
            // gesture events rather than here, so extra pointers are ignored on purpose.
            if (touch.pointerId != 0L) continue
            val point = AirDrawPoint(
                x = engine.viewport.toDocumentX(touch.x.toFloat() * view.width),
                y = engine.viewport.toDocumentY(touch.y.toFloat() * view.width),
                pressure = (touch.pressure ?: 1.0).toFloat(),
                timeMs = touch.timestamp ?: System.currentTimeMillis()
            )
            when (touch.phase) {
                AepTouchPhase.DOWN -> { engine.begin(StrokeSource.TOUCH, point); drawingWithTouch = true }
                AepTouchPhase.MOVE -> if (drawingWithTouch) engine.extend(point)
                AepTouchPhase.UP -> { if (drawingWithTouch) engine.end(); drawingWithTouch = false }
                AepTouchPhase.CANCEL -> { engine.cancel(); drawingWithTouch = false }
            }
        }
    }

    /**
     * Both capabilities arrive through the generic path, because AEP wraps only pose and live
     * person ergonomically. That is not a limitation here: the generated parsers turn a payload
     * into a typed model or return null, so a `ready` event - which carries no frame - simply
     * fails to parse and is ignored without anyone having to test for it.
     */
    private fun onCapabilityEvent(event: AepCapabilityEvent) {
        val payload = AepPayloadJson.decode(event.payloadJson) ?: return
        when (event.capability) {
            "camera.hand" -> AepHandFrame.fromMap(payload)?.let { latestHandFrame.set(it) }
            "input.touch" -> AepTouch.fromMap(payload)?.let {
                synchronized(pendingTouches) { pendingTouches += it }
            }
        }
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
