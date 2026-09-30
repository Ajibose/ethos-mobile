package com.ethosprotocol

import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.models.VaultPage
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.VaultUiState
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.services.ConnectionState
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import com.ethosprotocol.services.VaultEventSocket
import android.content.Context
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val vaultEventSocket: VaultEventSocket = mockk()
    private val expiringVaultsManager: ExpiringVaultsManager = mockk(relaxed = true)
    private val offlineCache: OfflineCache = mockk(relaxed = true)
    private val fakeNetworkMonitor = FakeNetworkMonitor(startOnline = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var vm: VaultViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(PendingActionSyncWorker.Companion)
        every { PendingActionSyncWorker.schedule(any()) } just Runs
        every { vaultEventSocket.events(any<String>()) } returns emptyFlow()
        every { vaultEventSocket.events(any<List<String>>()) } returns emptyFlow()
        every { vaultEventSocket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED).asStateFlow()
        every { expiringVaultsManager.bannerState } returns MutableStateFlow(null).asStateFlow()
        vm = VaultViewModel(
            apiClient = apiClient,
            notificationHelper = notificationHelper,
            pendingActionDao = pendingActionDao,
            vaultEventSocket = vaultEventSocket,
            expiringVaultsManager = expiringVaultsManager,
            offlineCache = offlineCache,
            networkMonitor = fakeNetworkMonitor,
            context = context
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(PendingActionSyncWorker.Companion)
    }

    @Test
    fun `load success updates vaults`() = runTest {
        val vaults = listOf(makeVault("v1"), makeVault("v2"))
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextCursor = null, hasMore = false))

        vm.load()

        assertEquals(vaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasMore)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `load network unavailable sets offline flag`() = runTest {
        coEvery { apiClient.listVaults(limit = 20) } returns ApiResult.NetworkUnavailable

        vm.load()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `load cancelled mid-request does not surface an error`() = runTest {
        // Simulates the screen (and viewModelScope) being torn down while
        // apiClient.listVaults() is in flight — the coroutine should stop silently
        // instead of writing a stray error/loading update to dead state.
        coEvery { apiClient.listVaults(limit = 20) } throws CancellationException("scope cancelled")

        vm.load()

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun `load error sets error message`() = runTest {
        coEvery { apiClient.listVaults(limit = 20) } returns ApiResult.Error("Server error", 500)

        vm.load()

        assertEquals("Server error", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadMore appends the next page and advances the cursor`() = runTest {
        val page1Vaults = listOf(makeVault("v1"), makeVault("v2"))
        val page2Vaults = listOf(makeVault("v3"))
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(page1Vaults, nextCursor = "cursor-1", hasMore = true))
        coEvery { apiClient.listVaults(limit = 20, after = "cursor-1") } returns
            ApiResult.Success(VaultPage(page2Vaults, nextCursor = null, hasMore = false))
        vm.load()

        vm.loadMore()

        assertEquals(page1Vaults + page2Vaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoadingMore)
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `loadMore does nothing when there is no next page`() = runTest {
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(makeVault("v1")), nextCursor = null, hasMore = false))
        vm.load()

        vm.loadMore()

        // load() itself already calls listVaults once with after=null, which any()
        // also matches — asserting a total count of 1 (not 0) is what actually proves
        // loadMore() triggered no additional call.
        coVerify(exactly = 1) { apiClient.listVaults(any(), any()) }
    }

    @Test
    fun `checkIn success refreshes only the checked-in vault`() = runTest {
        val v1 = makeVault("v1")
        val v2 = makeVault("v2")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1, v2), nextCursor = null, hasMore = false))
        vm.load()

        val refreshedV1 = v1.copy(lastCheckIn = "2026-05-01T00:00:00Z", ttlRemaining = 2_592_000L)
        coEvery { apiClient.checkIn("v1", any()) } returns ApiResult.Success(Unit)
        coEvery { apiClient.getVault("v1") } returns ApiResult.Success(refreshedV1)

        vm.checkIn("v1")

        coVerify { apiClient.checkIn("v1", any()) }
        coVerify { apiClient.getVault("v1") }
        coVerify(exactly = 1) { apiClient.listVaults(limit = 20) }
        coVerify(exactly = 0) { apiClient.getVault("v2") }
        assertEquals(listOf(refreshedV1, v2), vm.state.value.vaults)
    }

    @Test
    fun `checkIn network unavailable queues a pending action and schedules sync`() = runTest {
        coEvery { apiClient.checkIn("v1", any()) } returns ApiResult.NetworkUnavailable
        coEvery { pendingActionDao.getAll() } returns emptyList()

        vm.checkIn("v1")

        assertNotNull(vm.state.value.error)
        coVerify {
            pendingActionDao.insert(match {
                it.type == PendingActionType.CHECK_IN && it.vaultId == "v1" && it.dedupeKey == "check_in:v1"
            })
        }
        verify { PendingActionSyncWorker.schedule(context) }
    }

    @Test
    fun `createVault success reloads vaults`() = runTest {
        val vaults = listOf(makeVault("v1"))
        coEvery { apiClient.createVault(any(), any()) } returns ApiResult.Success(makeVault("v1"))
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextCursor = null, hasMore = false))

        vm.createVault("GXYZ", 30)

        coVerify { apiClient.createVault(any(), any()) }
        coVerify { apiClient.listVaults(limit = 20) }
        assertFalse(vm.state.value.isCreatingVault)
    }

    @Test
    fun `createVault network unavailable queues a pending action and schedules sync`() = runTest {
        coEvery { apiClient.createVault(any(), any()) } returns ApiResult.NetworkUnavailable
        coEvery { pendingActionDao.getAll() } returns emptyList()

        vm.createVault("GXYZ", 30)

        assertNotNull(vm.state.value.error)
        coVerify {
            pendingActionDao.insert(match {
                it.type == PendingActionType.CREATE_VAULT && it.payloadJson != null
            })
        }
        verify { PendingActionSyncWorker.schedule(context) }
        assertFalse(vm.state.value.isCreatingVault)
    }

    @Test
    fun `two rapid createVault calls while the first is in flight only submit once`() = runTest {
        val gate = CompletableDeferred<ApiResult<Vault>>()
        coEvery { apiClient.createVault(any(), any()) } coAnswers { gate.await() }

        // First call starts and suspends on the network response (gate not yet completed).
        vm.createVault("GXYZ", 30)
        assertTrue(vm.state.value.isCreatingVault)

        // A second tap while the first is still in flight must not reach the api at all.
        vm.createVault("GXYZ", 30)

        gate.complete(ApiResult.NetworkUnavailable)

        coVerify(exactly = 1) { apiClient.createVault(any(), any()) }
        coVerify(exactly = 1) { pendingActionDao.insert(any()) }
    }

    @Test
    fun `two createVault calls with different arguments both queue separately once offline`() = runTest {
        coEvery { apiClient.createVault(any(), any()) } returns ApiResult.NetworkUnavailable
        coEvery { pendingActionDao.getAll() } returns emptyList()

        vm.createVault("GXYZ", 30)
        vm.createVault("GABC", 60)

        coVerify(exactly = 2) { apiClient.createVault(any(), any()) }
        coVerify(exactly = 2) { pendingActionDao.insert(any()) }
    }

    // MARK: - #112 Pagination tests

    @Test
    fun `loadAll single page accumulates vaults and stops`() = runTest {
        val vaults = List(5) { makeVault("v$it") }
        val page = com.ethosprotocol.models.VaultPage(vaults = vaults, nextCursor = null, hasMore = false)
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.Success(page)

        vm.loadAll()

        assertEquals(vaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `loadAll multiple pages accumulates all vaults`() = runTest {
        val page1Vaults = List(20) { makeVault("p1-v$it") }
        val page2Vaults = List(7) { makeVault("p2-v$it") }
        val page1 = com.ethosprotocol.models.VaultPage(
            vaults = page1Vaults, nextCursor = "cursor-abc", hasMore = true
        )
        val page2 = com.ethosprotocol.models.VaultPage(
            vaults = page2Vaults, nextCursor = null, hasMore = false
        )
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.Success(page1)
        coEvery { apiClient.listVaults(limit = 20, after = "cursor-abc") } returns ApiResult.Success(page2)

        vm.loadAll()

        val allVaults = vm.state.value.vaults
        assertEquals(27, allVaults.size)
        assertEquals(page1Vaults + page2Vaults, allVaults)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadAll network unavailable on first page sets offline flag`() = runTest {
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.NetworkUnavailable

        vm.loadAll()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadAll network unavailable mid-pagination sets offline flag`() = runTest {
        val page1 = com.ethosprotocol.models.VaultPage(
            vaults = List(20) { makeVault("p1-v$it") }, nextCursor = "cursor-mid", hasMore = true
        )
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.Success(page1)
        coEvery { apiClient.listVaults(limit = 20, after = "cursor-mid") } returns ApiResult.NetworkUnavailable

        vm.loadAll()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadAll large vault list fixture 100 vaults across 5 pages`() = runTest {
        // Shared large-vault-list fixture: 100 vaults in 5 pages of 20.
        val allVaults = List(100) { makeVault("large-v$it") }
        val pages = allVaults.chunked(20)
        for (i in pages.indices) {
            val cursor = if (i == 0) null else "cursor-${i - 1}"
            val nextCursor = if (i < pages.lastIndex) "cursor-$i" else null
            val hasMore = i < pages.lastIndex
            coEvery {
                apiClient.listVaults(limit = 20, after = cursor)
            } returns ApiResult.Success(
                com.ethosprotocol.models.VaultPage(vaults = pages[i], nextCursor = nextCursor, hasMore = hasMore)
            )
        }

        vm.loadAll()

        assertEquals(100, vm.state.value.vaults.size)
        assertEquals(allVaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `updateBeneficiary success sets beneficiaryUpdated and reloads vaults`() = runTest {
        val v1 = makeVault("v1")
        val v2 = makeVault("v2")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1, v2), nextCursor = null, hasMore = false))
        vm.load()

        val updatedV1 = v1.copy(beneficiary = "GNEWBENEFICIARY")
        coEvery { apiClient.updateBeneficiary("v1", "GNEWBENEFICIARY") } returns ApiResult.Success(updatedV1)
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(updatedV1, v2), nextCursor = null, hasMore = false))

        vm.updateBeneficiary("v1", "GNEWBENEFICIARY")

        coVerify { apiClient.updateBeneficiary("v1", "GNEWBENEFICIARY") }
        assertTrue(vm.state.value.beneficiaryUpdated)
        assertEquals(listOf(updatedV1, v2), vm.state.value.vaults)
    }

    @Test
    fun `clearBeneficiaryUpdated resets the flag`() = runTest {
        val v1 = makeVault("v1")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1), nextCursor = null, hasMore = false))
        coEvery { apiClient.updateBeneficiary("v1", "GNEW") } returns ApiResult.Success(v1.copy(beneficiary = "GNEW"))
        vm.updateBeneficiary("v1", "GNEW")
        assertTrue(vm.state.value.beneficiaryUpdated)

        vm.clearBeneficiaryUpdated()

        assertFalse(vm.state.value.beneficiaryUpdated)
    }

    // #218: label update mirrors updateBeneficiary's flag-and-reload pattern.
    @Test
    fun `updateLabel success sets labelUpdated and reloads vaults`() = runTest {
        val v1 = makeVault("v1")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1), nextCursor = null, hasMore = false))
        vm.load()

        val labeled = v1.copy(label = "Emergency Fund")
        coEvery { apiClient.updateVaultLabel("v1", "Emergency Fund") } returns ApiResult.Success(labeled)
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(labeled), nextCursor = null, hasMore = false))

        vm.updateLabel("v1", "Emergency Fund")

        coVerify { apiClient.updateVaultLabel("v1", "Emergency Fund") }
        assertTrue(vm.state.value.labelUpdated)
        assertEquals("Emergency Fund", vm.state.value.vaults.first().label)
    }

    @Test
    fun `updateLabel with null clears the label`() = runTest {
        val v1 = makeVault("v1").copy(label = "Old Label")
        coEvery { apiClient.updateVaultLabel("v1", null) } returns ApiResult.Success(v1.copy(label = null))
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1.copy(label = null)), nextCursor = null, hasMore = false))

        vm.updateLabel("v1", null)

        coVerify { apiClient.updateVaultLabel("v1", null) }
        assertNull(vm.state.value.vaults.first().label)
    }

    @Test
    fun `updateBeneficiary error sets error message`() = runTest {
        coEvery { apiClient.updateBeneficiary("v1", "GNEW") } returns ApiResult.Error("Server error", 500)

        vm.updateBeneficiary("v1", "GNEW")

        assertEquals("Server error", vm.state.value.error)
    }

    @Test
    fun `updateBeneficiary network unavailable sets error`() = runTest {
        coEvery { apiClient.updateBeneficiary("v1", "GNEW") } returns ApiResult.NetworkUnavailable

        vm.updateBeneficiary("v1", "GNEW")

        assertNotNull(vm.state.value.error)
    }

    // #223: a poll (refreshSingle, via getVault) and a `vault_updated` push (the
    // vaultEventSocket flow) both funnel through updateVaultInPlace. Whichever is
    // applied last always wins outright — see the "Reconciling a poll/push
    // disagreement" rule in shared/api-contract.md.

    @Test
    fun `poll then push disagreement, push wins when applied last`() = runTest {
        val eventsFlow = MutableSharedFlow<VaultEvent>(replay = 1)
        every { vaultEventSocket.events("v1") } returns eventsFlow
        val v1 = makeVault("v1")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1), nextCursor = null, hasMore = false))
        vm.load()

        // A poll response lands first.
        coEvery { apiClient.getVault("v1") } returns ApiResult.Success(v1.copy(balance = 20_000_000L))
        vm.refreshSingle("v1")

        // A `vault_updated` push disagrees and arrives after.
        eventsFlow.emit(VaultEvent(type = "vault_updated", vault = v1.copy(balance = 30_000_000L)))

        assertEquals(30_000_000L, vm.state.value.vaults.first { it.id == "v1" }.balance)
    }

    @Test
    fun `push then poll disagreement, poll wins when applied last`() = runTest {
        val eventsFlow = MutableSharedFlow<VaultEvent>(replay = 1)
        every { vaultEventSocket.events("v1") } returns eventsFlow
        val v1 = makeVault("v1")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1), nextCursor = null, hasMore = false))
        vm.load()

        // A `vault_updated` push lands first.
        eventsFlow.emit(VaultEvent(type = "vault_updated", vault = v1.copy(balance = 30_000_000L)))

        // A poll response disagrees and arrives after.
        coEvery { apiClient.getVault("v1") } returns ApiResult.Success(v1.copy(balance = 20_000_000L))
        vm.refreshSingle("v1")

        assertEquals(20_000_000L, vm.state.value.vaults.first { it.id == "v1" }.balance)
    }

    private fun makeVault(id: String) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
        status = VaultStatus.active
    )
}
