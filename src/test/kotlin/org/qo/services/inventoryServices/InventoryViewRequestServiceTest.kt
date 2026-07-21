package org.qo.services.inventoryServices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryViewRequestServiceTest {
	private val service = InventoryViewRequestService()

	@Test
	fun requestCanOnlyBeConsumedOnceAfterApproval() {
		val request = assertNotNull(service.create("Owner", "Viewer"))
		assertFalse(request.approved)
		assertNull(service.consume(request.secret))

		assertNotNull(service.approve(request.secret))
		assertTrue(assertNotNull(service.status(request.secret)).approved)
		val consumed = assertNotNull(service.consume(request.secret))
		assertEquals("Owner", consumed.owner)
		assertEquals("Viewer", consumed.viewer)
		assertNull(service.status(request.secret))
		assertNull(service.consume(request.secret))
	}

	@Test
	fun duplicateOwnerViewerPairIsRejectedCaseInsensitively() {
		assertNotNull(service.create("Owner", "Viewer"))
		assertNull(service.create("owner", "viewer"))
	}
}
