package io.github.elkelvinx.sashabridge.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import io.github.elkelvinx.sashabridge.settings.Appearance
import io.github.elkelvinx.sashabridge.stats.StatsPage
import kotlinx.coroutines.launch

/**
 * The history screen, with two tabs that can also be swiped:
 * - **Historial** ([HistoryListPage]): the plays of the last 30 days, grouped by day, with a
 *   search. Touching one opens it in its player ([PlayerLinks]).
 * - **Estadísticas** ([StatsPage]): how much music, which apps, which songs and artists.
 *
 * Both follow the history while the screen is visible.
 *
 * Main thread.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var history: HistoryRepository
    private lateinit var apps: AppNames

    /** The pages, once the pager made them. */
    private var listPage: HistoryListPage? = null
    private var statsPage: StatsPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.applyAccent(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.historyRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.historyToolbar).setNavigationOnClickListener { finish() }
        history = HistoryRepository.get(this)
        apps = AppNames(packageManager)

        val pager = findViewById<ViewPager2>(R.id.historyPager)
        pager.adapter = Pages()
        // Both pages stay made, so the statistics are ready when swiped to.
        pager.offscreenPageLimit = 1
        TabLayoutMediator(findViewById<TabLayout>(R.id.historyTabs), pager) { tab, position ->
            tab.setText(PAGES[position].title)
        }.attach()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { history.changes.collect { listPage?.reload() } }
                launch { history.statsChanges.collect { statsPage?.load() } }
            }
        }
    }

    /** Opens [entry] in its player, or says it cannot. */
    private fun open(entry: HistoryEntry) {
        if (!PlayerLinks.open(this, entry)) {
            Toast.makeText(this, getString(R.string.history_open_failed, apps.label(entry.source)), Toast.LENGTH_SHORT).show()
        }
    }

    /** The two pages of the pager, each made once from its layout. */
    private inner class Pages : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = PAGES.size

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(PAGES[viewType].layout, parent, false)
            val thumbnails = ThumbnailStore.get(this@HistoryActivity)
            when (viewType) {
                LIST -> listPage = HistoryListPage(view, lifecycleScope, history, apps, thumbnails, ::open)
                else -> statsPage = StatsPage(view, lifecycleScope, history, apps, thumbnails, ::open)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    /** A page: the title of its tab and its layout. */
    private class Page(@param:StringRes val title: Int, @param:LayoutRes val layout: Int)

    private companion object {
        const val LIST = 0
        val PAGES = listOf(
            Page(R.string.history_tab_list, R.layout.page_history),
            Page(R.string.history_tab_stats, R.layout.page_stats),
        )
    }
}
