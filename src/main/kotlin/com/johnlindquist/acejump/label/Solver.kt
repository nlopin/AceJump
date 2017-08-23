package com.johnlindquist.acejump.label

import com.google.common.collect.HashBiMap
import com.google.common.collect.Multimaps
import com.google.common.collect.Ordering
import com.google.common.collect.TreeMultimap
import com.intellij.openapi.diagnostic.Logger
import com.johnlindquist.acejump.search.wordBoundsPlus
import com.johnlindquist.acejump.view.Model.editorText
import com.johnlindquist.acejump.view.Model.viewBounds
import java.lang.Math.max
import java.lang.Math.min
import kotlin.collections.set
import kotlin.system.measureTimeMillis

/**
 * Solves the tag assignment problem. The tag assignment problem can be stated
 * thusly: Given a set of indices I in document D, and a set of two-character
 * tags T, find a mapping of T->I such that D[i_n..i_(n+1)] + t_n[0] is not in
 * {D[i_j..(i_(j+1) + 1)]}. Maximize |T->I|. (TODO: improve notation here)
 */

object Solver {
  private val logger = Logger.getInstance(Solver::class.java)
  private var bigrams: MutableSet<String> = LinkedHashSet()
  private var newTags: MutableMap<String, Int> = HashBiMap.create()
  private var strings: Set<String> = hashSetOf()

  /**
   * Iterates through the remaining available tags, until we find one that
   * matches our criteria, i.e. does not collide with an existing tag or
   * plaintext string. To have the desired behavior, this has a surprising
   * number of edge cases that must explicitly prevented.
   *
   * @param tag the tag string which is to be assigned
   * @param sites potential indices where a tag may be assigned
   */

  private fun tryToAssignTag(tag: String, sites: Collection<Int>): Boolean {
    if (newTags.containsKey(tag)) return false
    val index = sites.firstOrNull { index ->
      val (left, right) = editorText.wordBoundsPlus(index)

      //TODO: do this check in the tagger to simplify contract
      fun hasNearbyTag(index: Int) =
        Pair(max(left, index - 2), min(right, index + 2))
          .run { (first..second).any { newTags.containsValue(it) } }

      !hasNearbyTag(index)
    } ?: return false

    newTags[tag] = index
    return true
  }

  private val tagOrder: Comparator<String> = compareBy(
    { it[0].isDigit() || it[1].isDigit() },
    { eligibleSitesByTag[it].size },
    { Pattern.distance(it[0], it.last()) },
    { Pattern.priority(it.first()) }
  )

  /**
   * Sorts jump targets to determine which positions get first choice for tags,
   * by taking into account the structure of the surrounding text. For example,
   * if the jump target is the first letter in a word, it is advantageous to
   * prioritize this location (in case we run out of tags), since the user is
   * more likely to target words by their leading character than not.
   */

  private val siteOrder: Comparator<Int> = compareBy(
    // Sites in immediate view should come first
    { it !in viewBounds },
    // Ensure that the first letter of a word is prioritized for tagging
    { editorText[max(0, it - 1)].isLetterOrDigit() },
    { it })

  /**
   * Enforces tag conservation precedence. Tags have certain restrictions during
   * assignment, ie. not all tags may be assigned to all sites. Therefore, we
   * must spend our tag "budget" wisely, in order to cover the most sites with
   * the tags we have at our disposal. We should consider the "most restrictive"
   * tags first, since they have the least chance of being available as more
   * sites are assigned.
   *
   * Tags which are compatible with the fewest sites should have precedence for
   * first assignment. Here we ensure that scarce tags are prioritized for their
   * subsequent binding to available sites.
   *
   * @see isCompatibleWithSite This defines how tags may be assigned to sites.
   */


  private val eligibleSitesByTag = Multimaps.synchronizedSetMultimap(
    TreeMultimap.create<String, Int>(Ordering.natural(), siteOrder))


  /**
   * Maps tags to search results. Tags *must* have the following properties:
   *
   * 1. A tag must not match *any* bigrams on the screen.
   * 2. A tag's 1st letter must not match any letters of the covered word.
   * 3. Tag must not match any combination of any plaintext and tag. "e(a[B)X]"
   * 4. Once assigned, a tag must never change until it has been selected. *A.
   *
   * Tags *should* have the following properties:
   *
   * A. Should be as short as possible. A tag may be "compacted" later.
   * B. Should prefer keys that are physically closer to the last key pressed.
   *
   * @param results All indices to be tagged
   *
   * @return A list of all tags and their corresponding indices
   */

  fun solve(results: Set<Int>, tags: Set<String>): Map<String, Int> {
    newTags = HashBiMap.create(Pattern.NUM_TAGS)
    bigrams = tags.toMutableSet()
    eligibleSitesByTag.clear()

    strings = HashSet(results.map { getWordFragments(it) }.flatten())
    val tagsByFirstLetter = bigrams.groupBy { it[0] }

    val timeElapsed = measureTimeMillis {
      results.parallelStream().forEach { site ->
        tagsByFirstLetter.entries.forEach { (firstLetter, tags) ->
          if (site isCompatibleWithTagChar firstLetter)
            tags.forEach { tag -> eligibleSitesByTag.put(tag, site) }
        }
      }

      val sortedTags = eligibleSitesByTag.keySet().toList().sortedWith(tagOrder)

      var totalAssigned = 0
      for (tagString in sortedTags) {
        val eligibleSites = eligibleSitesByTag[tagString]
        if (totalAssigned == results.size) break
        else if (eligibleSites.isEmpty()) Tagger.full = false
        else if (tryToAssignTag(tagString, eligibleSites)) totalAssigned++
      }
    }

    logger.info("results size: ${results.size}")
    logger.info("newTags size: ${newTags.size}")
    logger.info("Time elapsed: $timeElapsed ms")

    return newTags
  }

  // Provides a way to short-circuit the full text search if a match is found
  private operator fun String.contains(key: String) =
    Tagger.textMatches.any { regionMatches(it, key, 0, key.length) }

  /**
   * Returns true IFF the tag, when inserted at any position in the word, could
   * match an existing substring elsewhere in the editor text. We should never
   * use a tag which can be partly completed by typing plaintext.
   */

  private infix fun Int.isCompatibleWithTagChar(char: Char) =
    getWordFragments(this).map { it + char }.none { it in strings }

  private fun getWordFragments(site: Int): List<String> {
    val left = site + Tagger.query.length - 1
    val right = editorText.wordBoundsPlus(site).second

    return (left..right).map { editorText.substring(left, it) }
  }
}