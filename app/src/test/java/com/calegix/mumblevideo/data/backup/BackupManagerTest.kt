package com.calegix.mumblevideo.data.backup

import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.ThemeMode
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    private fun manager(graph: TestGraph) = BackupManager(
        graph.servers,
        graph.certificates,
        graph.trust,
        graph.settings,
        graph.core::inspectCertificate,
        clock = { 42L },
    )

    /** A source install with a bit of everything, including a selected real PKCS#12 certificate. */
    private suspend fun TestScope.populated(): Pair<TestGraph, ClientCertificate> {
        val graph = TestGraph(this, AppSettings(trueBlack = true, cameraId = "1"))
        graph.servers.save(
            SavedServer(
                label = "Home",
                host = "mumble.example.org",
                port = 64738,
                username = "nate",
                password = "pw-home",
                favourite = true,
                accessTokens = listOf("alpha", "beta"),
                lastConnectedEpochMillis = 1_000L,
            ),
        )
        graph.servers.save(SavedServer(label = "Other", host = "other.example.org", port = 1234))
        val cert = throwawayPkcs12("Nate", "p12-pass")
        val info = graph.core.inspectCertificate(cert.pkcs12, cert.password)
        val id = graph.certificates.add("My cert", cert, info, at = 7L)
        graph.settings.update { it.copy(selectedCertificateId = id) }
        graph.trust.trust("aa".repeat(32), ServerAddress("mumble.example.org", 64738), 11L)
        graph.trust.trust("bb".repeat(32), ServerAddress("other.example.org", 1234), 12L)
        return graph to cert
    }

    @Test
    fun `export then import into an empty install restores everything`() = runTest {
        val (source, cert) = populated()
        val text = manager(source).export()

        // The documented field names, which hand-made backups rely on.
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(
            setOf(
                "format",
                "version",
                "exported_at",
                "servers",
                "certificates",
                "trusted_server_certificates",
                "settings",
                "selected_certificate_sha1",
            ),
            root.keys,
        )
        assertEquals(
            setOf("label", "host", "port", "username", "password", "favourite", "access_tokens", "last_connected"),
            root["servers"]!!.jsonArray[0].jsonObject.keys,
        )
        assertEquals(
            setOf("name", "password", "common_name", "sha1", "not_after", "created_at", "pkcs12_base64"),
            root["certificates"]!!.jsonArray[0].jsonObject.keys,
        )
        assertEquals(
            setOf("sha256", "host", "port", "accepted_at"),
            root["trusted_server_certificates"]!!.jsonArray[0].jsonObject.keys,
        )

        val target = TestGraph(this)
        val summary = manager(target).import(text)

        assertEquals("Imported 2 servers, 1 certificate, 2 trusted servers, settings", summary.describe())
        assertEquals(
            source.servers.servers.first().map { it.copy(id = 0) },
            target.servers.servers.first().map { it.copy(id = 0) },
        )
        val restored = target.certificates.certificates.first().single()
        val original = source.certificates.certificates.first().single()
        assertEquals(original.copy(id = 0), restored.copy(id = 0))
        val material = target.certificates.load(restored.id)!!
        assertArrayEquals(cert.pkcs12, material.pkcs12)
        assertEquals("p12-pass", material.password)
        assertEquals(source.trust.all().toSet(), target.trust.all().toSet())
        val settings = target.settings.settings.value
        assertTrue(settings.trueBlack)
        assertEquals("1", settings.cameraId)
        assertEquals(restored.id, settings.selectedCertificateId)
    }

    @Test
    fun `importing the same backup twice changes nothing the second time`() = runTest {
        val (source, _) = populated()
        val text = manager(source).export()
        val target = TestGraph(this)
        val backup = manager(target)

        backup.import(text)
        val servers = target.servers.servers.first()
        val certificates = target.certificates.certificates.first()
        val trusted = target.trust.all().toSet()
        val settings = target.settings.settings.value

        backup.import(text)
        assertEquals(servers, target.servers.servers.first())
        assertEquals(certificates, target.certificates.certificates.first())
        assertEquals(trusted, target.trust.all().toSet())
        assertEquals(settings, target.settings.settings.value)
    }

    @Test
    fun `a server already in the list is updated, not duplicated`() = runTest {
        val (source, _) = populated()
        val text = manager(source).export()
        val target = TestGraph(this)
        val existingId = target.servers.save(
            SavedServer(
                label = "Old label",
                host = "MUMBLE.example.org",
                port = 64738,
                username = "nate",
                password = "old",
            ),
        )
        target.servers.save(SavedServer(label = "Mine", host = "mine.example.org"))

        manager(target).import(text)

        val servers = target.servers.servers.first()
        assertEquals(3, servers.size)
        val merged = servers.single { it.id == existingId }
        assertEquals("Home", merged.label)
        assertEquals("pw-home", merged.password)
        assertEquals(listOf("alpha", "beta"), merged.accessTokens)
        assertTrue(merged.favourite)
        assertTrue(servers.any { it.host == "mine.example.org" })
    }

    @Test
    fun `unknown, missing and unreadable settings are ignored`() = runTest {
        val target = TestGraph(this, AppSettings(themeMode = ThemeMode.Dark, bitrate = 24_000, defaultUsername = "Me"))
        val text = backupJson(
            settings = """{"trueBlack":true,"aSettingFromTheFuture":{"x":1},"themeMode":"Neon","bitrate":"loud"}""",
        )

        val summary = manager(target).import(text)

        val settings = target.settings.settings.value
        assertTrue(summary.settings)
        assertTrue(settings.trueBlack)
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertEquals(24_000, settings.bitrate)
        assertEquals("Me", settings.defaultUsername)
    }

    @Test
    fun `the selected certificate is matched by SHA-1, not by the old id`() = runTest {
        val target = TestGraph(this)
        val mine = throwawayPkcs12("Already here", "")
        target.certificates.add("Already here", mine, target.core.inspectCertificate(mine.pkcs12, ""), at = 1L)
        val theirs = throwawayPkcs12("Nate", "secret")
        val sha1 = target.core.inspectCertificate(theirs.pkcs12, "secret").sha1
        val encoded = Base64.getEncoder().encodeToString(theirs.pkcs12)
        // The old install's id 1 is a different certificate here.
        val text = backupJson(
            certificates = """[{"name":"Phone","password":"secret","common_name":"Nate","sha1":"$sha1",
                "not_after":0,"created_at":5,"pkcs12_base64":"$encoded"}]""",
            settings = """{"selectedCertificateId":1}""",
            selected = "\"$sha1\"",
        )

        manager(target).import(text)

        val imported = target.certificates.certificates.first().single { it.sha1 == sha1 }
        assertEquals("Phone", imported.name)
        assertEquals(imported.id, target.settings.settings.value.selectedCertificateId)
        assertTrue(imported.id != 1L)
    }

    @Test
    fun `a certificate that will not open is skipped and reported`() = runTest {
        val target = TestGraph(this)
        val text = backupJson(
            certificates = """[{"name":"Bad","password":"","sha1":"00","pkcs12_base64":"AAECAw=="}]""",
        )

        val summary = manager(target).import(text)

        assertEquals(0, summary.certificates)
        assertEquals(1, summary.unreadableCertificates)
        assertTrue(target.certificates.certificates.first().isEmpty())
        assertNull(target.settings.settings.value.selectedCertificateId)
    }

    @Test
    fun `other files and newer versions are refused`() = runTest {
        val target = TestGraph(this)
        val backup = manager(target)
        suspend fun refusal(text: String): String = try {
            backup.import(text)
            "accepted"
        } catch (e: BackupException) {
            e.message.orEmpty()
        }

        assertTrue(refusal("not json").contains("not a mumble-video backup"))
        assertTrue(refusal("""{"format":"something-else","version":1}""").contains("not a mumble-video backup"))
        assertTrue(refusal(backupJson(version = 2)).contains("newer version"))
        assertTrue(target.servers.servers.first().isEmpty())
    }

    private fun backupJson(
        version: Int = 1,
        certificates: String = "[]",
        settings: String = "{}",
        selected: String = "null",
    ) = """
        {"format":"mumble-video-backup","version":$version,"exported_at":1,
         "servers":[],"certificates":$certificates,"trusted_server_certificates":[],
         "settings":$settings,"selected_certificate_sha1":$selected}
    """.trimIndent()

    /** A self-signed RSA certificate packed as PKCS#12, made fresh for each test. */
    private fun throwawayPkcs12(commonName: String, password: String): ClientCertificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 86_400_000L),
            name,
            keys.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("mumble", keys.private, password.toCharArray(), arrayOf(certificate))
        val out = ByteArrayOutputStream()
        store.store(out, password.toCharArray())
        return ClientCertificate(out.toByteArray(), password)
    }
}
