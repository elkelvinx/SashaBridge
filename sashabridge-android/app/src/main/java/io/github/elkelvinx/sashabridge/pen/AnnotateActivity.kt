package io.github.elkelvinx.sashabridge.pen

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.graphics.drawable.AnimationDrawable
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
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
 * The "Mover" button also holds "Cambiar tamaño": a double tap or a long press on it opens a
 * small menu to choose which of the two it is. "Imagen" places a photo from the phone on the
 * capture, to move, resize and draw over.
 *
 * Other apps can also share an image to SashaBridge ("Compartir"): it opens here as if the PC
 * had sent it, and "Enviar" puts it on the PC's clipboard, at once or as soon as a PC connects.
 * After sending or discarding it, the screen closes and the user is back in that app.
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
    private lateinit var moveButton: ImageButton
    private lateinit var colorButtons: List<View>
    private lateinit var thicknessButtons: List<ImageButton>

    /** Capture from the PC being annotated, or `null`. */
    private var capture: PcMessage.AnnotateRequest? = null

    /** Whether the image being annotated was shared from another app instead. */
    private var shared = false

    /** Whether this screen was opened to annotate a shared image, so it closes after it. */
    private var openedByShare = false

    /** Whether an image is open, from the PC or shared. */
    private val isOpen: Boolean get() = capture != null || shared

    /** Whether the screen is visible. */
    private var started = false

    /** Whether an image is being decoded or encoded, which blocks other actions. */
    private var busy = false

    /** Whether the PC has the `/pen` route open, so a capture can be sent. */
    private var pcConnected = false

    /** Tool of the "Mover" button: [InkTool.MOVE] or [InkTool.RESIZE]. */
    private var moveSlot = InkTool.MOVE

    /** When the "Mover" button was last tapped, to tell a double tap. */
    private var lastMoveTap = 0L

    /** The system photo picker; needs no permission to read the photos. */
    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::insertPicture)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_annotate)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = findViewById(R.id.inkCanvas)
        waitingPanel = findViewById(R.id.waitingPanel)
        (findViewById<ImageView>(R.id.waitingSasha).drawable as? AnimationDrawable)?.start()
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
        moveButton = findViewById(R.id.toolMove)
        moveButton.setOnClickListener { onMoveButtonTap() }
        moveButton.setOnLongClickListener {
            showMoveTools()
            true
        }
        findViewById<View>(R.id.toolImage).setOnClickListener {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
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
                if (isOpen) showActions() else finish()
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
        openShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openShared(intent)
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
        if (isOpen || busy) {
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

    /**
     * Opens an image another app shared (`ACTION_SEND`), unless another one is open. Photos
     * shrink to [SHARED_MAX_SIDE] on their longer side; screenshots fit as they are.
     */
    private fun openShared(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        // Handled once, even if the screen is recreated.
        intent.action = null
        if (isOpen || busy) {
            Toast.makeText(this, R.string.annotate_share_busy, Toast.LENGTH_LONG).show()
            return
        }
        openedByShare = true
        busy = true
        lifecycleScope.launch {
            val bitmap = decode(uri, SHARED_MAX_SIDE)
            busy = false
            if (bitmap == null) {
                Toast.makeText(this@AnnotateActivity, R.string.annotate_image_error, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            shared = true
            canvas.setCapture(bitmap)
            showEditor(true)
        }
    }

    /** Places a photo the user picked on the open capture, held by the move tool. */
    private fun insertPicture(uri: Uri) {
        val maxSide = canvas.captureSize()?.let { (w, h) -> max(w, h) } ?: return
        lifecycleScope.launch {
            val picture = decode(uri, maxSide)
            if (picture == null) {
                Toast.makeText(this@AnnotateActivity, R.string.annotate_image_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!isOpen) return@launch
            setMoveSlot(InkTool.MOVE)
            canvas.insertPicture(picture)
        }
    }

    /**
     * Decodes the image at [uri] into software memory, so it can be drawn into the exported
     * capture, no larger than [maxSide] on its longer side.
     *
     * @return The image, or `null` if it cannot be read.
     */
    private suspend fun decode(uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longer = max(info.size.width, info.size.height)
                if (longer > maxSide) {
                    val scale = maxSide.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Cannot decode the image $uri", e)
            null
        } catch (e: SecurityException) {
            // The app that shared it took the permission back.
            Log.w(LOG_TAG, "No permission to read the image $uri", e)
            null
        }
    }

    /**
     * Exports the annotated image and sends it: to the capture it answers, or, for a shared
     * image, to the PC's clipboard now or as soon as a PC connects.
     */
    private fun send() {
        if (!isOpen) return
        val current = capture
        val annotated = canvas.export() ?: return
        busy = true
        lifecycleScope.launch {
            val data = withContext(Dispatchers.Default) {
                val png = ByteArrayOutputStream()
                annotated.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, png)
                Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
            }
            val message = if (current != null) {
                annotations.reply(PhoneMessage.AnnotateResult(current.requestId, PNG_MIME, annotated.width, annotated.height, data))
                R.string.annotate_sent
            } else {
                annotations.share(PhoneMessage.AnnotateShared(PNG_MIME, annotated.width, annotated.height, data))
                if (pcConnected) R.string.annotate_sent else R.string.annotate_shared_pending
            }
            busy = false
            Toast.makeText(this@AnnotateActivity, message, Toast.LENGTH_SHORT).show()
            closeCapture()
        }
    }

    /** Offers sending or discarding the open capture; touching outside keeps annotating. */
    // The sheet's content has no parent until setContentView, which sets its layout params.
    @SuppressLint("InflateParams")
    private fun showActions() {
        if (!isOpen || busy) return
        val dialog = BottomSheetDialog(this)
        val sheet = LayoutInflater.from(this).inflate(R.layout.sheet_annotate_actions, null)
        sheet.findViewById<View>(R.id.sendAction).apply {
            // A shared image waits for the PC; a capture's answer needs the PC that asked.
            isEnabled = pcConnected || shared
            setOnClickListener {
                dialog.dismiss()
                send()
            }
        }
        sheet.findViewById<TextView>(R.id.sendOffline).apply {
            isVisible = !pcConnected
            setText(if (shared) R.string.annotate_shared_offline else R.string.annotate_send_offline)
        }
        sheet.findViewById<View>(R.id.discardAction).setOnClickListener {
            dialog.dismiss()
            confirmDiscard()
        }
        dialog.setContentView(sheet)
        dialog.behavior.skipCollapsed = true
        // Open in full: in landscape the peek height hides part of it, and dragging it up from
        // the bottom edge is the system's home gesture.
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    /** Asks whether to discard the open image; without one, leaves the screen. */
    private fun confirmDiscard() {
        if (!isOpen) return finish()
        if (!canvas.canUndo) {
            discard()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.annotate_discard_title)
            .setPositiveButton(R.string.annotate_discard) { _, _ -> discard() }
            .setNegativeButton(R.string.annotate_cancel, null)
            .show()
    }

    /** Tells the PC its capture was discarded, if it was one, and closes the image. */
    private fun discard() {
        capture?.let { annotations.reply(PhoneMessage.AnnotateCancelled(it.requestId)) }
        closeCapture()
    }

    /**
     * Empties the canvas and waits again, opening a capture that arrived meanwhile; after a
     * shared image, goes back to the app that shared it.
     */
    private fun closeCapture() {
        capture = null
        shared = false
        canvas.setCapture(null)
        if (openedByShare) {
            finish()
            return
        }
        showEditor(false)
        if (annotations.pendingCapture.value != null) onCaptureArrived()
    }

    /**
     * Asks for the text and size of a note tapped at capture coordinates (x, y), or of the
     * note [editing] that was tapped, which can also be deleted.
     */
    // The dialog's content has no parent until the dialog shows it.
    @SuppressLint("InflateParams")
    private fun askForText(x: Float, y: Float, editing: InkText?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text, null)
        val input = view.findViewById<EditText>(R.id.textInput)
        val sizes = view.findViewById<MaterialButtonToggleGroup>(R.id.textSizeGroup)
        val step = editing?.let { canvas.textStep(it.size) } ?: canvas.defaultTextStep
        sizes.check(TEXT_SIZE_BUTTONS[step])
        editing?.let { input.setText(it.text) }
        val place = {
            val chosen = TEXT_SIZE_BUTTONS.indexOf(sizes.checkedButtonId).coerceAtLeast(0)
            canvas.placeText(x, y, input.text.toString(), canvas.textSize(chosen), editing)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (editing == null) R.string.annotate_text_title else R.string.annotate_text_edit_title)
            .setView(view)
            .setPositiveButton(if (editing == null) R.string.annotate_text_place else R.string.annotate_text_save) { _, _ -> place() }
            .setNegativeButton(R.string.annotate_cancel, null)
        if (editing != null) {
            dialog.setNeutralButton(R.string.annotate_text_delete) { _, _ -> canvas.placeText(x, y, "", editing.size, editing) }
        }
        dialog.show()
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
        annotations.setArmed(started && !isOpen)
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
        moveButton.isSelected = tool.holdsMarks
    }

    /** A tap picks the button's tool; a second tap soon after opens the menu of its tools. */
    private fun onMoveButtonTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveTap < ViewConfiguration.getDoubleTapTimeout()) {
            lastMoveTap = 0L
            showMoveTools()
        } else {
            lastMoveTap = now
            selectTool(moveSlot)
        }
    }

    /** Opens, over the "Mover" button, the menu to choose between moving and resizing. */
    private fun showMoveTools() {
        PopupMenu(this, moveButton, Gravity.TOP).apply {
            menuInflater.inflate(R.menu.menu_move_tools, menu)
            setForceShowIcon(true)
            menu.findItem(if (moveSlot == InkTool.RESIZE) R.id.moveToolResize else R.id.moveToolMove).isChecked = true
            setOnMenuItemClickListener { item ->
                setMoveSlot(if (item.itemId == R.id.moveToolResize) InkTool.RESIZE else InkTool.MOVE)
                true
            }
            show()
        }
    }

    /** Makes [tool] the tool of the "Mover" button, with its icon, and selects it. */
    private fun setMoveSlot(tool: InkTool) {
        moveSlot = tool
        val resize = tool == InkTool.RESIZE
        moveButton.setImageResource(if (resize) R.drawable.ic_resize else R.drawable.ic_move)
        moveButton.contentDescription = getString(if (resize) R.string.annotate_tool_resize else R.string.annotate_tool_move)
        selectTool(tool)
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

        /** Longer side a shared photo shrinks to: sharp on any monitor, quick over USB. */
        const val SHARED_MAX_SIDE = 3840
        const val DISABLED_ALPHA = 0.35f
        const val SWATCH_DP = 28f
        const val SWATCH_MARGIN_DP = 5f
        const val SWATCH_IDLE_SCALE = 0.72f
        const val SWATCH_ANIMATION_MS = 120L
        const val BAR_MARGIN_DP = 12f

        /** The size buttons of the note dialog, from small to huge, as [InkCanvasView.textSize] steps. */
        val TEXT_SIZE_BUTTONS = listOf(R.id.textSizeSmall, R.id.textSizeMedium, R.id.textSizeLarge, R.id.textSizeHuge)
    }
}
