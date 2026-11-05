package com.teatalk.xs.ui.characters

import com.teatalk.xs.data.db.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.26.0：成人内容开关对角色列表的影响。 */
class CharacterNsfwFilterTest {

    private fun card(id: Long, name: String, tags: String) = CharacterEntity(
        id = id,
        name = name,
        avatarPath = null,
        cardJson = "{}",
        cardSource = "{}",
        tagsJson = tags,
        firstMes = "",
        createdAt = 1L,
        favorite = false,
        pinned = false,
        updatedAt = 1L,
        linkedWorldBookIdsJson = "[]",
    )

    private val cards = listOf(
        card(1, "Tea Trio", """["日常"]"""),
        card(2, "Night", """["nsfw","剧情"]"""),
        card(3, "MixedCase", """["NSFW"]"""),
    )

    @Test
    fun `nsfw cards are hidden when the switch is off`() {
        val visible = applyFilters(cards, "", CharacterFilter.ALL, null, allowNsfw = false)
        assertEquals(listOf("Tea Trio"), visible.map { it.name })
    }

    @Test
    fun `nsfw cards are visible when the switch is on`() {
        val visible = applyFilters(cards, "", CharacterFilter.ALL, null, allowNsfw = true)
        assertEquals(3, visible.size)
    }

    @Test
    fun `tag filter still hides nsfw when off`() {
        val visible = applyFilters(cards, "", CharacterFilter.ALL, "nsfw", allowNsfw = false)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `other filters keep working with nsfw off`() {
        val favorites = applyFilters(cards, "tea", CharacterFilter.RECENT, null, allowNsfw = false)
        assertEquals(listOf("Tea Trio"), favorites.map { it.name })
    }
}
