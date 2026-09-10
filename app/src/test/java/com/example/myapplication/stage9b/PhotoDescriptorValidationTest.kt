package com.example.myapplication.stage9b

import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.validatePhotoBytes
import com.example.myapplication.stage5.validatePhotoDescriptor
import org.junit.Assert.*
import org.junit.Test

class PhotoDescriptorValidationTest {
    @Test fun descriptorOnlyPathUsesTheSameCompleteValidation() {
        val bytes = Stage4PhotoFixture.jpegBytes()
        val owned = validatePhotoBytes(bytes)
        assertEquals(owned.descriptor, validatePhotoDescriptor(bytes, expected = owned.descriptor))
        assertArrayEquals(bytes, owned.bytes)
        assertNotSame(bytes, owned.bytes)
    }

    @Test fun expectedDescriptorMismatchFailsBothPaths() {
        val bytes = Stage4PhotoFixture.jpegBytes()
        val descriptor = validatePhotoDescriptor(bytes)
        val wrong = descriptor.copy(sha256 = "0".repeat(64))
        assertThrows(Stage5ValidationException::class.java) { validatePhotoDescriptor(bytes, wrong) }
        assertThrows(Stage5ValidationException::class.java) { validatePhotoBytes(bytes, wrong) }
    }

    @Test fun incompleteContainerStillFailsBothPaths() {
        val truncated = Stage4PhotoFixture.jpegBytes().dropLast(2).toByteArray()
        assertThrows(Stage5ValidationException::class.java) { validatePhotoDescriptor(truncated) }
        assertThrows(Stage5ValidationException::class.java) { validatePhotoBytes(truncated) }
    }

    @Test fun byteOwningValidationStillDetachesCallerStorage() {
        val caller = Stage4PhotoFixture.jpegBytes()
        val original = caller.copyOf()
        val validated = validatePhotoBytes(caller)
        caller.fill(0)
        assertArrayEquals(original, validated.bytes)
        assertEquals(validatePhotoDescriptor(original), validated.descriptor)
    }
}
