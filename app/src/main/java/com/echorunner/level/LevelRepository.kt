package com.echorunner.level

import android.content.res.AssetManager
import com.echorunner.core.LevelSpec
import org.json.JSONObject

/** One chapter of Story mode, as declared in `assets/levels/manifest.json`. */
data class Chapter(
    val number: Int,
    val title: String,
    val subtitle: String,
    val levelIds: List<String>,
)

/**
 * Loads Story-mode levels from `assets/levels/`.
 *
 * Adding a level is a two-file change with no code edit: drop `<id>.json` in the assets folder
 * and list the id in the chapter's `levels` array in the manifest. That is the whole point of
 * the data-driven format — the remaining levels of the game are content, not engineering.
 *
 * Parsed levels are cached, because re-entering a level after a death or a restart is common
 * and re-parsing JSON on that path would show up as a hitch on the transition.
 */
class LevelRepository(private val assets: AssetManager) {

    private val cache = HashMap<String, LevelSpec>()
    private var chaptersCache: List<Chapter>? = null

    fun chapters(): List<Chapter> = chaptersCache ?: parseManifest().also { chaptersCache = it }

    /** Flat, play-order list of every level id across all chapters. */
    fun levelIds(): List<String> = chapters().flatMap { it.levelIds }

    fun load(levelId: String): LevelSpec = cache.getOrPut(levelId) {
        val path = "levels/$levelId.json"
        val text = try {
            assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            throw IllegalStateException("cannot read level asset '$path'", t)
        }
        LevelParser.parse(text)
    }

    fun indexOf(levelId: String): Int = levelIds().indexOf(levelId)

    /** The next level in play order, or null when [current] is the last one. */
    fun nextLevelId(current: String): String? {
        val ids = levelIds()
        val i = ids.indexOf(current)
        return if (i < 0 || i >= ids.lastIndex) null else ids[i + 1]
    }

    fun chapterOf(levelId: String): Chapter? =
        chapters().firstOrNull { levelId in it.levelIds }

    private fun parseManifest(): List<Chapter> {
        val text = try {
            assets.open(MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            throw IllegalStateException("cannot read '$MANIFEST'", t)
        }

        val root = JSONObject(text)
        val array = root.optJSONArray("chapters")
            ?: throw IllegalStateException("$MANIFEST is missing a 'chapters' array")

        val out = ArrayList<Chapter>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val idsArray = o.optJSONArray("levels")
            val ids = ArrayList<String>(idsArray?.length() ?: 0)
            if (idsArray != null) {
                for (j in 0 until idsArray.length()) {
                    idsArray.optString(j).takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
            out.add(
                Chapter(
                    number = o.optInt("number", i + 1),
                    title = o.optString("title", "Chapter ${i + 1}"),
                    subtitle = o.optString("subtitle", ""),
                    levelIds = ids,
                )
            )
        }
        return out
    }

    private companion object {
        const val MANIFEST = "levels/manifest.json"
    }
}
