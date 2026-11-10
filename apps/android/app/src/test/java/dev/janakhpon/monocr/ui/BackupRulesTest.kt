package dev.janakhpon.monocr.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Cloud backup takes the same data on every Android version, and a
 * device-to-device transfer takes scan history wherever Android can tell the
 * two apart.
 *
 * `data_extraction_rules.xml` (Android 12+) limited cloud backup to shared
 * preferences while `backup_rules.xml` (Android 11 and older) still sent the
 * database and files, so scan history left the device on older phones only.
 * `requireFlags="deviceToDeviceTransfer"` exists from API 28, so Android 9 to
 * 11 (`xml-v28`) keep the history transfer; Android 7 and 8 cannot without
 * also sending it to cloud backup, so they transfer settings only.
 */
class BackupRulesTest {
    private fun includes(path: String, parentTag: String? = null): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val root: Element = if (parentTag == null) doc.documentElement
        else doc.getElementsByTagName(parentTag).item(0) as Element
        val nodes = root.getElementsByTagName("include")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Domains every backup takes: includes with no `requireFlags`. */
    private fun unflagged(path: String) =
        includes(path).filter { !it.hasAttribute("requireFlags") }.map { it.getAttribute("domain") }.toSet()

    private fun deviceTransferOnly(path: String) =
        includes(path).filter { it.getAttribute("requireFlags") == "deviceToDeviceTransfer" }
            .map { it.getAttribute("domain") }.toSet()

    private val cloud = includes("src/main/res/xml/data_extraction_rules.xml", "cloud-backup")
        .map { it.getAttribute("domain") }.toSet()
    private val transfer = includes("src/main/res/xml/data_extraction_rules.xml", "device-transfer")
        .map { it.getAttribute("domain") }.toSet()

    @Test fun `older Android backs up only what Android 12 cloud backup does`() {
        assertEquals(setOf("sharedpref"), cloud)
        // API 24 to 27 ignore requireFlags, so any include there reaches cloud backup.
        assertEquals(emptyList<Element>(), includes("src/main/res/xml/backup_rules.xml").filter { it.hasAttribute("requireFlags") })
        assertEquals(cloud, unflagged("src/main/res/xml/backup_rules.xml"))
        assertEquals(cloud, unflagged("src/main/res/xml-v28/backup_rules.xml"))
    }

    @Test fun `Android 9 to 11 transfer to a new device what Android 12 does`() {
        assertEquals(setOf("root"), transfer)
        assertEquals(transfer, deviceTransferOnly("src/main/res/xml-v28/backup_rules.xml"))
    }
}
