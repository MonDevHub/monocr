package dev.janakhpon.monocr.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Cloud backup takes the same data on every Android version.
 *
 * `data_extraction_rules.xml` (Android 12+) limited cloud backup to shared
 * preferences while `backup_rules.xml` (Android 11 and older) still sent the
 * database and files, so scan history left the device on older phones only.
 */
class BackupRulesTest {
    private fun includedDomains(path: String, parentTag: String? = null): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val root: Element = if (parentTag == null) doc.documentElement
        else doc.getElementsByTagName(parentTag).item(0) as Element
        val includes = root.getElementsByTagName("include")
        return (0 until includes.length).map { (includes.item(it) as Element).getAttribute("domain") }.toSet()
    }

    @Test fun `older Android backs up only what Android 12 cloud backup does`() {
        val legacy = includedDomains("src/main/res/xml/backup_rules.xml")
        val cloud = includedDomains("src/main/res/xml/data_extraction_rules.xml", "cloud-backup")
        assertEquals(setOf("sharedpref"), cloud)
        assertEquals(cloud, legacy)
    }
}
