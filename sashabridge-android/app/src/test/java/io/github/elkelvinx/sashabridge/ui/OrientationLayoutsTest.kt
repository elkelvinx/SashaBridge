package io.github.elkelvinx.sashabridge.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The portrait and landscape versions of a screen must agree on the class of every view they
 * share an id for. On rotation Android saves each view's state by id and restores it into the
 * new layout's view with that id; a view of another class there closes the app ("Wrong state
 * class"). The queue button once was an ImageButton in portrait and a MaterialButton in
 * landscape, and turning the phone upright closed SashaBridge.
 *
 * Reads the layout files as they are in the source, `<include>` resolved the way Android
 * resolves them: the landscape version of an included file if there is one.
 */
class OrientationLayoutsTest {

    private val res = File("src/main/res")

    @Test
    fun sharedIdsKeepTheirViewClassOnRotation() {
        val landscapeLayouts = File(res, "layout-land").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("no landscape layouts found under ${res.absolutePath}", landscapeLayouts.isNotEmpty())
        for (landscape in landscapeLayouts) {
            val portrait = File(res, "layout/${landscape.name}")
            if (!portrait.exists()) continue
            val portraitIds = viewsById(portrait, "layout")
            val landscapeIds = viewsById(landscape, "layout-land")
            val mismatches = (portraitIds.keys intersect landscapeIds.keys)
                .filter { portraitIds[it] != landscapeIds[it] }
                .map { "$it: ${portraitIds[it]} in portrait, ${landscapeIds[it]} in landscape" }
            assertEquals("${landscape.name} changes the class of a view with the same id", emptyList<String>(), mismatches)
        }
    }

    /** Every view with an id in [file] and the layouts it includes, by id, as its class name. */
    private fun viewsById(file: File, folder: String): Map<String, String> {
        val views = LinkedHashMap<String, String>()
        collect(parse(file).documentElement, folder, views)
        return views
    }

    private fun collect(element: Element, folder: String, views: MutableMap<String, String>) {
        if (element.tagName == "include") {
            val name = element.getAttribute("layout").removePrefix("@layout/")
            val included = File(res, "$folder/$name.xml").takeIf { it.exists() } ?: File(res, "layout/$name.xml")
            collect(parse(included).documentElement, folder, views)
            return
        }
        idOf(element)?.let { views[it] = element.tagName }
        val children = element.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let { collect(it, folder, views) }
        }
    }

    private fun idOf(element: Element): String? =
        element.getAttribute("android:id").takeIf { it.startsWith("@+id/") }?.removePrefix("@+id/")

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
}
