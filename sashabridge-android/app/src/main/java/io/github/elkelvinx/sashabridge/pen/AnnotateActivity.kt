package io.github.elkelvinx.sashabridge.pen

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Anotar": annotates captures from the PC with the S Pen and sends them back.
 *
 * While the screen is visible and has no capture open, it is "armed": the PC sends the images
 * the user copies (for example with Win+Shift+S) without being asked. A capture opens as soon
 * as it arrives; another one arriving meanwhile waits until this one is sent or discarded.
 * "+" offers "Enviar", which returns the annotated PNG to the PC for its clipboard, and
 * "Descartar". While a capture is open the system bars hide and the back gesture opens those
 * same choices instead of leaving, so a pen stroke that starts at the edge loses nothing.
 *
 * Main thread, except decoding and encoding the images, which run on [Dispatchers.Default].
 */
class AnnotateActivity : AppCompatActivity() {

    private val annotations = AnnotationRepository.shared

    private lateinit var canvas: InkCanvasView
    private lateinit var waitingPanel: View
    private lateinit var penWarning: TextView
    private lateinit var closeButton: View
    private lateinit var bottomBar: View
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var toolButtons: Map<InkTool, ImageButton>
    private lateinit var colorButtons: List<View>
    private lateinit var thicknessButtons: List<ImageButton>

    /** Capture being annotated, or `null` while waiting. */
    private var capture: PcMessage.AnnotateRequest? = null

    /** Whether the screen is visible. */
    private var started = false

    /** Whether an image is being decoded or encoded, which blocks other actions. */
    private var busy = false

    /** Whether the PC has the `/pen` route open, so a capture can be sent. */
    private var pcConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_annotate)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = findViewById(R.id.inkCanvas)
        waitingPanel = findViewById(R.id.waitingPanel)
        penWarning = findViewById(R.id.penWarning)
        closeButton = findViewById(R.id.closeButton)
        bottomBar = findViewById(R.id.bottomBar)
        undoButton = findViewById(R.id.undoButton)
        redoButton = findViewById(R.id.redoButton)
        applyInsets()

        toolButtons = mapOf(
            InkTool.PEN to findViewById(R.id.toolPen),
            InkTool.HIGHLIGHTER to findViewById(R.id.toolHighlighter),
            InkTool.LINE to findViewById(R.id.toolLine),
            InkTool.RECTANGLE to findViewById(R.id.toolRectangle),
            InkTool.ARROW to findViewById(R.id.toolArrow),
            InkTool.TEXT to findViewById(R.id.toolText),
        )
        toolButtons.forEach { (tool, button) -> button.setOnClickListener { selectTool(tool) } }
        colorButtons = buildColorButtons()
        thicknessButtons = listOf(findViewById(R.id.thinButton), findViewById(R.id.mediumButton), findViewById(R.id.thickButton))
        thicknessButtons.forEachIndexed { index, button -> button.setOnClickListener { selectThickness(index) } }

        closeButton.setOnClickListener { finish() }
        findViewById<View>(R.id.actionsButton).setOnClickListener { showActions() }
        undoButton.setOnClickListener { canvas.undo() }
        redoButton.setOnClickListener { canvas.redo() }
        canvas.onChange = ::updateHistoryButtons
        canvas.onTextRequest = ::askForText
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Edge strokes can still trip the gesture; it never throws the work away.
                if (capture != null) showActions() else finish()
            }
        })

        selectTool(InkTool.PEN)
        selectColor(0)
        selectThickness(1)
        showEditor(false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { annotations.pendingCapture.collect { if (it != null) onCaptureArrived() } }
                launch {
                    annotations.penConnected.collect { connected ->
                        pcConnected = connected
                        penWarning.isVisible = !connected
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        updateArmed()
    }

    override fun onStop() {
        started = false
        updateArmed()
        super.onStop()
    }

    /** Opens the pending capture, unless one is already open. */
    private fun onCaptureArrived() {
        if (capture != null || busy) {
            Toast.makeText(this, R.string.annotate_queued, Toast.LENGTH_SHORT).show()
            return
        }
        val next = annotations.take() ?: return
        CaptureNotifier(this).cancel()
        busy = true
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                val bytes = Base64.decode(next.data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.copy(Bitmap.Config.ARGB_8888, false)
            }
            busy = false
            if (bitmap == null) {
                annotations.reply(PhoneMessage.AnnotateCancelled(next.requestId))
                return@launch
            }
            capture = next
            canvas.setCapture(bitmap)
            showEditor(true)
        }
    }

    /** Exports the annotated capture and sends it to the PC. */
    private fun send() {
        val current = capture ?: return
        val annotated = canvas.export() ?: return
        busy = true
        lifecycleScope.launch {
            val data = withContext(Dispatchers.Default) {
                val png = ByteArrayOutputStream()
                annotated.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, png)
                Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
            }
            annotations.reply(
                PhoneMessage.AnnotateResult(current.requestId, PNG_MIME, annotated.width, annotated.height, data),
            )
            busy = false
            Toast.makeText(this@AnnotateActivity, R.string.annotate_sent, Toast.LENGTH_SHORT).show()
            closeCapture()
        }
    }

    /** Offers sending or discarding the open capture; touching outside keeps annotating. */
    // The sheet's content has no parent until setContentView, which sets its layout params.
    @SuppressLint("InflateParams")
    private fun showActions() {
        if (capture == null || busy) return
        val dialog = BottomSheetDialog(this)
        val sheet = LayoutInflater.from(this).inflate(R.layout.sheet_annotate_actions, null)
        sheet.findViewById<View>(R.id.sendAction).apply {
            isEnabled = pcConnected
            setOnClickListener {
                dialog.dismiss()
                send()
            }
        }
        sheet.findViewById<View>(R.id.sendOffline).isVisible = !pcConnected
        sheet.findViewById<View>(R.id.discardAction).setOnClickListener {
            dialog.dismiss()
            confirmDiscard()
        }
        dialog.setContentView(sheet)
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    /** Asks whether to discard the open capture; without one, leaves the screen. */
    private fun confirmDiscard() {
        val current = capture ?: return finish()
        if (!canvas.canUndo) {
            discard(current)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.annotate_discard_title)
            .setPositiveButton(R.string.annotate_discard) { _, _ -> discard(current) }
            .setNegativeButton(R.string.annotate_cancel, null)
            .show()
    }

    /** Tells the PC the capture was discarded and goes back to waiting. */
    private fun discard(current: PcMessage.AnnotateRequest) {
        annotations.reply(PhoneMessage.AnnotateCancelled(current.requestId))
        closeCapture()
    }

    /** Empties the canvas, waits again, and opens a capture that arrived meanwhile. */
    private fun closeCapture() {
        capture = null
        canvas.setCapture(null)
        showEditor(false)
        if (annotations.pendingCapture.value != null) onCaptureArrived()
    }

    /** Asks for the text of a note tapped at capture coordinates (x, y). */
    private fun askForText(x: Float, y: Float) {
        val input = EditText(this).apply {
            hint = getString(R.string.annotate_text_hint)
            minLines = 2
        }
        val padding = (resources.displayMetrics.density * DIALOG_PADDING_DP).toInt()
        val frame = LinearLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.annotate_text_title)
            .setView(frame)
            .setPositiveButton(R.string.annotate_text_place) { _, _ -> canvas.addText(x, y, input.text.toString()) }
            .setNegativeButton(R.string.annotate_cancel, null)
            .show()
        input.requestFocus()
    }

    /**
     * Shows the canvas and its bar, full screen, while a capture is open; otherwise the waiting
     * panel with the system bars back.
     */
    private fun showEditor(editing: Boolean) {
        waitingPanel.isVisible = !editing
        closeButton.isVisible = !editing
        canvas.isVisible = editing
        bottomBar.isVisible = editing
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // A swipe from the top or bottom edge shows the bars for a moment over the canvas.
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (editing) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        updateHistoryButtons()
        updateArmed()
    }

    /** Armed while visible and waiting: the PC may send copied images then. */
    private fun updateArmed() {
        annotations.setArmed(started && capture == null)
    }

    private fun updateHistoryButtons() {
        undoButton.isEnabled = canvas.canUndo
        undoButton.alpha = if (canvas.canUndo) 1f else DISABLED_ALPHA
        redoButton.isEnabled = canvas.canRedo
        redoButton.alpha = if (canvas.canRedo) 1f else DISABLED_ALPHA
    }

    private fun selectTool(tool: InkTool) {
        canvas.tool = tool
        toolButtons.forEach { (each, button) -> button.isSelected = each == tool }
    }

    private fun selectColor(index: Int) {
        canvas.color = ContextCompat.getColor(this, INK_COLORS[index])
        colorButtons.forEachIndexed { i, button ->
            val selected = i == index
            button.isSelected = selected
            button.animate().scaleX(if (selected) 1f else SWATCH_IDLE_SCALE)
                .scaleY(if (selected) 1f else SWATCH_IDLE_SCALE).setDuration(SWATCH_ANIMATION_MS).start()
        }
        thicknessButtons.forEach { it.imageTintList = ColorStateList.valueOf(canvas.color) }
    }

    private fun selectThickness(index: Int) {
        canvas.thickness = index
        thicknessButtons.forEachIndexed { i, button -> button.isSelected = i == index }
    }

    /** Creates one round swatch per ink color in the bottom bar. */
    private fun buildColorButtons(): List<View> {
        val row = findViewById<LinearLayout>(R.id.colorRow)
        val size = (resources.displayMetrics.density * SWATCH_DP).toInt()
        val margin = (resources.displayMetrics.density * SWATCH_MARGIN_DP).toInt()
        return INK_COLORS.mapIndexed { index, colorRes ->
            View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.color_swatch)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
                contentDescription = getString(R.string.annotate_color, index + 1)
                setOnClickListener { selectColor(index) }
                row.addView(this, LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) })
            }
        }
    }

    /** Keeps the buttons clear of the system bars, while shown, and of the camera cutout. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.annotateRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = (resources.displayMetrics.density * BAR_MARGIN_DP).toInt()
            closeButton.updateLayoutParams<FrameLayout.LayoutParams> {
                setMargins(margin + bars.left, margin + bars.top, margin + bars.right, margin)
            }
            bottomBar.updateLayoutParams<FrameLayout.LayoutParams> {
                setMargins(margin + bars.left, margin, margin + bars.right, margin + bars.bottom)
            }
            insets
        }
    }

    private companion object {
        val INK_COLORS = listOf(R.color.ink_red, R.color.ink_orange, R.color.ink_green, R.color.ink_blue, R.color.ink_purple)
        const val PNG_MIME = "image/png"
        const val PNG_QUALITY = 100
        const val DISABLED_ALPHA = 0.35f
        const val SWATCH_DP = 28f
        const val SWATCH_MARGIN_DP = 5f
        const val SWATCH_IDLE_SCALE = 0.72f
        const val SWATCH_ANIMATION_MS = 120L
        const val BAR_MARGIN_DP = 12f
        const val DIALOG_PADDING_DP = 20f
    }
}
