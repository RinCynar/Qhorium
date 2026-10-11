package rj.qmce.lite.fix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class KtFixTest {
    @Test
    fun plus_addsPairWithoutMutatingInput() {
        val original = linkedMapOf<Any?, Any?>("first" to 1)

        val result = KtFix.plus(original, "second" to 2)

        assertEquals(linkedMapOf<Any?, Any?>("first" to 1, "second" to 2), result)
        assertEquals(listOf("first", "second"), result.keys.toList())
        assertEquals(linkedMapOf<Any?, Any?>("first" to 1), original)
    }

    @Test
    fun plus_replacesValueForExistingKeyAndKeepsOrder() {
        val original = linkedMapOf<Any?, Any?>("first" to 1, "second" to 2)

        val result = KtFix.plus(original, "first" to 3)

        assertEquals(linkedMapOf<Any?, Any?>("first" to 3, "second" to 2), result)
        assertEquals(listOf("first", "second"), result.keys.toList())
        assertEquals(linkedMapOf<Any?, Any?>("first" to 1, "second" to 2), original)
    }

    @Test
    fun plus_supportsEmptyMapAndNullablePairEntries() {
        val original = emptyMap<Any?, Any?>()

        val result = KtFix.plus(original, null to null)

        assertEquals(1, result.size)
        assertTrue(result.containsKey(null))
        assertNull(result[null])
        assertTrue(original.isEmpty())
    }

    @Test
    fun plus_exposesExpectedStaticJvmSignature() {
        val method = KtFix::class.java.getDeclaredMethod("plus", Map::class.java, Pair::class.java)

        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(Map::class.java, method.returnType)
        assertFalse(method.isSynthetic)
    }
}
