package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.keepCollecting
import com.calegix.mumblevideo.settle
import com.calegix.mumblevideo.ui.settings.CertificatesViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CertificatesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `generating stores and selects the certificate`() = runTest {
        val graph = TestGraph(this)
        val vm = CertificatesViewModel(graph.core, graph.certificates, graph.settings) { 5L }
        keepCollecting(vm.uiState)

        vm.generate("Nate")
        settle()

        val cert = vm.uiState.value.certificates.single()
        assertEquals("Nate", cert.commonName)
        assertEquals(cert.id, vm.uiState.value.selectedId)
        assertEquals(cert.id, graph.settings.settings.value.selectedCertificateId)
    }

    @Test
    fun `importing garbage reports an error and stores nothing`() = runTest {
        val graph = TestGraph(this)
        val vm = CertificatesViewModel(graph.core, graph.certificates, graph.settings)
        keepCollecting(vm.uiState)

        vm.import(byteArrayOf(1, 2, 3), password = "", displayName = "")
        settle()

        assertNotNull(vm.uiState.value.message)
        assertEquals(0, vm.uiState.value.certificates.size)
    }

    @Test
    fun `deleting the selected certificate clears the selection, export hands out the bytes`() = runTest {
        val graph = TestGraph(this)
        val vm = CertificatesViewModel(graph.core, graph.certificates, graph.settings)
        keepCollecting(vm.uiState)
        vm.generate("Nate")
        settle()
        val id = vm.uiState.value.certificates.single().id

        vm.prepareExport(id)
        settle()
        assertNotNull(vm.pendingExport.value)
        vm.exportFinished(true)
        assertNull(vm.pendingExport.value)

        vm.delete(id)
        settle()
        assertNull(graph.settings.settings.value.selectedCertificateId)
        assertEquals(0, vm.uiState.value.certificates.size)
    }

    @Test
    fun `the selected certificate is used when connecting`() = runTest {
        val graph = TestGraph(this)
        val vm = CertificatesViewModel(graph.core, graph.certificates, graph.settings)
        vm.generate("Nate")
        settle()
        with(graph) { connect() }
        // The fake core registers users who present a certificate.
        assertNotNull(graph.core.serverState.value.selfUser?.registeredId)
    }

    @Test
    fun `a first connect without any certificate makes one, but a deliberate none is kept`() = runTest {
        val graph = TestGraph(this)
        val session = com.calegix.mumblevideo.session.SessionController(
            graph.core,
            graph.settings,
            graph.servers,
            graph.certificates,
            graph.trust,
            backgroundScope,
            autoCertificate = true,
        )
        session.connect(TestGraph.SERVER)
        settle()
        assertNotNull(graph.settings.settings.value.selectedCertificateId)

        graph.settings.update { it.copy(selectedCertificateId = null) }
        session.connect(TestGraph.SERVER)
        settle()
        assertNull(graph.settings.settings.value.selectedCertificateId)
    }
}
