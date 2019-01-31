package org.wycliffeassociates.otter.common.domain.md

import java.io.BufferedReader

// TODO: Add Help type enum to HelpResource? (tn, tq)
data class HelpResource(var title: String, var body: String)

class ParseMd {
    companion object {

        private val isTitleRegex = Regex("^#+\\s*[^#\\s]+")
        private val titleTextRegex = Regex("^#+\\s*")

        fun parse(reader: BufferedReader): List<HelpResource> {
            val helpResourceList = ArrayList<HelpResource>()

            reader.forEachLine {
                if (it.isEmpty()) {
                    return@forEachLine // continue
                }
                // If we have a title, add a new help resource to the end of the list
                if (isTitleLine(it)) {
                    helpResourceList.add(HelpResource(it, ""))
                }
                // Found body text. Add the body to the help resource at the end of the list
                // If the list is empty, the body text will be discarded (this should never happen.)
                else if (helpResourceList.isNotEmpty()) {
                    if (helpResourceList.last().body.isNotEmpty()) {
                        helpResourceList.last().body += System.lineSeparator()
                    }
                    helpResourceList.last().body += it
                }
            }
            return helpResourceList
        }

        internal fun getTitleText(line: String): String? {
            titleTextRegex.find(line)?.let {
                return line.removePrefix(it.value)
            } ?: return null
        }

        internal fun isTitleLine(line: String): Boolean = isTitleRegex.containsMatchIn(line)
    }
}