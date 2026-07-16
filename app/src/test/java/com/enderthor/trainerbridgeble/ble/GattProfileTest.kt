package com.enderthor.trainerbridgeble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GattProfileTest {

    @Test fun identifiesPowerCharacteristics() {
        assertTrue(GattUuids.isPowerChar(GattUuids.uuid16(0x2AD2)))   // Indoor Bike Data
        assertTrue(GattUuids.isPowerChar(GattUuids.uuid16(0x2A63)))   // Cycling Power Measurement
        assertFalse(GattUuids.isPowerChar(GattUuids.uuid16(0x2AD9)))  // Control Point is not a power char
        assertFalse(GattUuids.isPowerChar(UUID.fromString("F03EE002-4910-473C-BE46-960948C2F59C")))
    }

    @Test fun identifiesControlCharacteristic() {
        assertTrue(GattUuids.carriesControl(GattUuids.uuid16(0x2AD9)))
        assertFalse(GattUuids.carriesControl(GattUuids.uuid16(0x2AD2)))
    }

    @Test fun identifiesStackProvidedServices() {
        assertTrue(GattUuids.isStackService(GattUuids.uuid16(0x1800)))
        assertTrue(GattUuids.isStackService(GattUuids.uuid16(0x1801)))
        assertFalse(GattUuids.isStackService(GattUuids.uuid16(0x1826)))  // FTMS is ours to mirror
    }

    @Test fun profileHoldsServiceStructure() {
        val prof = GattProfile(
            listOf(SvcSpec(GattUuids.uuid16(0x1826), true, listOf(CharSpec(GattUuids.INDOOR_BIKE_DATA, 0x10, 0x01))))
        )
        assertEquals(1, prof.services.size)
        assertEquals(GattUuids.INDOOR_BIKE_DATA, prof.services[0].chars[0].uuid)
    }
}
